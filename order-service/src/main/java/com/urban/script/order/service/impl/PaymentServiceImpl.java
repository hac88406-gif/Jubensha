package com.urban.script.order.service.impl;

import com.urban.script.common.BusinessException;
import com.urban.script.common.ResultCode;
import com.urban.script.common.SnowflakeIdGenerator;
import com.urban.script.order.dto.PayNotifyReq;
import com.urban.script.order.dto.PrepayReq;
import com.urban.script.order.dto.PrepayRes;
import com.urban.script.order.entity.OrderInfo;
import com.urban.script.order.entity.PaymentTransaction;
import com.urban.script.order.feign.RecommendClient;
import com.urban.script.order.mapper.OrderMapper;
import com.urban.script.order.mapper.PaymentMapper;
import com.urban.script.order.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 支付服务实现 —— 模拟支付闭环
 *
 * <p>演示链路：
 * <pre>
 *   POST /order/pay/prepay  （玩家，需登录）
 *      ① 校验订单归属 + 状态（status=0 待支付才允许支付）
 *      ② 幂等：已有待支付流水则复用，不重复生成
 *      ③ 雪花生成 payment_no，落 payment_transaction(status=0)
 *      ④ 返回 { paymentNo, orderNo, amount, channel, sign, payUrl }
 *
 *   POST /order/pay/notify  （模拟微信/支付宝服务器回调，网关白名单）
 *      ① HMAC-SHA256 验签（密钥从 Nacos urban-shared-config 注入）
 *      ② 参数一致性（orderNo / amount 与流水比对）
 *      ③ 幂等：流水已成功 / 订单已支付 → 直接返回，不重复入账
 *      ④ 订单原子置为已支付（UPDATE ... WHERE status=0）→ 流水置为成功
 *      ⑤ 订单已取消 → 拒绝本次支付（模拟层面提示退款必要性）
 * </pre>
 *
 * <p>设计要点（面试可讲）：
 * <ul>
 *   <li><b>回调验签</b>：HMAC-SHA256 共享密钥，防止伪造回调</li>
 *   <li><b>双幂等</b>：流水支付号唯一键 + 订单/流水「WHERE status=0 原子流转」，
 *       回调重复投递、并发竞态都不会重复入账</li>
 *   <li><b>超时关单天然兼容</b>：支付成功后订单 status=1，TTL 延迟消息到期时
 *       OrderTimeoutConsumer 的 id=0 幂等更新返回 0 行自动跳过，无需任何改动</li>
 * </ul>
 *
 * @author urban-script-reservation
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final PaymentMapper paymentMapper;
    private final OrderMapper orderMapper;
    /** 防并发 prepay 重复插流水用（pay:lock:{orderNo}）；Redis 不可用时锁降级放行 */
    private final RedissonClient redissonClient;
    /** 推荐服务 Feign：支付成功后同步「玩家-剧本」关系到 Neo4j */
    private final RecommendClient recommendClient;

    /** 模拟支付渠道标识 */
    private static final String CHANNEL_SIM = "SIM";

    /**
     * 回调验签密钥（敏感配置）。
     * <p>
     * 取值优先级：Nacos urban-shared-config 的 {@code pay.sign-secret} &gt;
     * 环境变量 {@code PAY_SIGN_SECRET} &gt; order-service application.yml 中的本地默认值。
     * <p>
     * ⚠️ 不再在源码中硬编码默认密钥：支付回调是免鉴权入口（靠 HMAC 验签自证），
     * 密钥一旦随仓库公开，任何人都能伪造「支付成功」回调 → 0 元下单。
     */
    @Value("${pay.sign-secret}")
    private String signSecret;

    // ========================================================================
    // ① 发起支付（prepay）
    // ========================================================================

    @Override
    public PrepayRes prepay(Long userId, PrepayReq req) {
        // ① 查订单 + 归属校验
        OrderInfo order = orderMapper.selectByOrderNo(req.getOrderNo());
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "订单不存在");
        }
        if (order.getUserId() == null || !order.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "无权操作他人订单");
        }
        if (order.getStatus() == null || order.getStatus() != 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),
                    order.getStatus() == 2 ? "订单已取消，无法支付" : "订单状态不允许支付");
        }

        // ② 幂等 + 防并发：加 pay:lock:{orderNo} 锁后查-插，避免 Select-then-Insert 竞态
        //    产生多条待支付流水（Redis 不可用时锁降级放行，重复流水幂等无害，不资损）
        PaymentTransaction pending;
        RLock payLock = redissonClient.getLock("pay:lock:" + order.getOrderNo());
        boolean locked = false;
        try {
            locked = payLock.tryLock(3, 5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[prepay] 获取支付锁失败（Redis 不可用），无锁执行 orderNo={}, reason={}",
                    order.getOrderNo(), e.getMessage());
        }
        try {
            pending = findOrCreatePayment(order, req, userId);
        } finally {
            if (locked) {
                try {
                    payLock.unlock();
                } catch (Exception unlockEx) {
                    // 锁最多 5s 自动过期，unlock 失败不影响业务
                    log.warn("[prepay] 释放支付锁失败，锁将随 leaseTime 自动过期 orderNo={}", order.getOrderNo());
                }
            }
        }

        // ③ 组装模拟支付参数（含签名，供 notify 回调验签）
        String sign = sign(pending.getPaymentNo(), order.getOrderNo(), order.getAmount(), CHANNEL_SIM);
        return PrepayRes.builder()
                .paymentNo(pending.getPaymentNo())
                .orderNo(order.getOrderNo())
                .amount(order.getAmount())
                .payMethod(pending.getPayMethod())
                .channel(CHANNEL_SIM)
                .sign(sign)
                .payUrl("https://pay.sim.example.com/cashier?paymentNo=" + pending.getPaymentNo())
                .build();
    }

    /**
     * 查该订单的待支付流水，没有则创建一条（prepay 幂等核心）
     *
     * <p>调用方应持有 {@code pay:lock:{orderNo}} 保证查-插串行；无锁降级调用也允许，
     * 重复插入的待支付流水不会资损（notify 按 paymentNo 幂等入账）。
     */
    private PaymentTransaction findOrCreatePayment(OrderInfo order, PrepayReq req, Long userId) {
        PaymentTransaction pending = paymentMapper.selectPendingByOrderNo(order.getOrderNo());
        if (pending == null) {
            pending = new PaymentTransaction();
            pending.setPaymentNo(String.valueOf(SnowflakeIdGenerator.getInstance().nextId()));
            pending.setOrderNo(order.getOrderNo());
            pending.setUserId(userId);
            pending.setAmount(order.getAmount());
            pending.setPayMethod(req.getPayMethod());
            pending.setStatus(PaymentTransaction.STATUS_PENDING);
            pending.setChannel(CHANNEL_SIM);
            paymentMapper.insert(pending);
            log.info("[prepay] 生成支付流水 paymentNo={}, orderNo={}, amount={}, payMethod={}",
                    pending.getPaymentNo(), order.getOrderNo(), order.getAmount(), req.getPayMethod());
        } else {
            log.info("[prepay] 复用待支付流水 paymentNo={}, orderNo={}", pending.getPaymentNo(), order.getOrderNo());
        }
        return pending;
    }

    // ========================================================================
    // ② 模拟支付平台回调（notify）
    // ========================================================================

    @Override
    public void notify(PayNotifyReq req) {
        // ① 验签（防伪造回调）
        if (!verify(req)) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "回调签名校验失败");
        }

        // ② 查流水 + 参数一致性
        PaymentTransaction pay = paymentMapper.selectByPaymentNo(req.getPaymentNo());
        if (pay == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "支付流水不存在");
        }
        if (!pay.getOrderNo().equals(req.getOrderNo())) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "回调订单号与流水不一致");
        }
        if (pay.getAmount().compareTo(req.decimalAmount()) != 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "回调金额与预支付金额不一致");
        }

        // ③ 幂等：流水已成功 → 重复回调，直接返回不重复入账
        if (pay.getStatus() != null && pay.getStatus() == PaymentTransaction.STATUS_SUCCESS) {
            log.info("[notify] 流水已成功，幂等跳过 paymentNo={}", req.getPaymentNo());
            return;
        }

        // ④ 订单状态校验 + 原子置为已支付
        OrderInfo order = orderMapper.selectByOrderNo(req.getOrderNo());
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "订单不存在");
        }
        if (order.getStatus() != null && order.getStatus() == 1) {
            // 订单已支付（可能是另一路回调先成功）→ 幂等返回
            log.info("[notify] 订单已支付，幂等跳过 orderNo={}", req.getOrderNo());
            return;
        }
        if (order.getStatus() != null && order.getStatus() == 2) {
            // 订单已取消（超时关单/手动取消/场次关闭）——模拟层面拒绝入账，真实系统此处触发退款
            log.warn("[notify] 订单已取消，支付作废 orderNo={}, status={}", req.getOrderNo(), order.getStatus());
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "订单已取消，本次支付作废");
        }

        // ⑤ 原子流转：订单 0→1（带幂等），成功后再给流水打成功标记
        int orderRows = orderMapper.updateStatusToPaid(req.getOrderNo());
        if (orderRows == 0) {
            // 竞态：另一路径已处理（已支付）→ 幂等返回
            log.info("[notify] 订单状态已变更，本次回调不重复入账 orderNo={}", req.getOrderNo());
            return;
        }
        int payRows = paymentMapper.tryMarkPaid(req.getPaymentNo());
        if (payRows == 0) {
            // 流水已被其他回调标记成功，不影响结果
            log.warn("[notify] 流水已被标记成功，跳过 paymentNo={}", req.getPaymentNo());
        }

        // ⑥ 异步同步「玩家-剧本」关系到 Neo4j（非核心链路，失败不影响支付结果）
        syncPlayedToGraph(order);

        log.info("[notify] ✅ 支付成功 paymentNo={}, orderNo={}, amount={}",
                req.getPaymentNo(), req.getOrderNo(), pay.getAmount());
    }

    /**
     * 同步玩家游玩关系到 Neo4j 知识图谱（用于协同过滤推荐）
     * <p>
     * 非核心链路：Feign 调用失败/超时仅记日志，不影响支付结果。
     * recommend-service 内部会自动失效该用户的推荐缓存。
     */
    private void syncPlayedToGraph(OrderInfo order) {
        if (order.getUserId() == null || order.getScriptId() == null) {
            return;
        }
        try {
            RecommendClient.OrderSyncReq syncReq = new RecommendClient.OrderSyncReq();
            syncReq.setUserId(order.getUserId());
            syncReq.setScriptId(order.getScriptId());
            // username 可留空，recommend-service 会兜底为 "user{id}"
            recommendClient.syncOrder(syncReq);
            log.info("[syncPlayedToGraph] 已同步 userId={} scriptId={}", order.getUserId(), order.getScriptId());
        } catch (Exception e) {
            // 降级：图谱同步失败不影响支付主链路
            log.warn("[syncPlayedToGraph] 同步 Neo4j 失败（已降级）userId={} scriptId={} err={}",
                    order.getUserId(), order.getScriptId(), e.getMessage());
        }
    }

    // ========================================================================
    // 签名工具（HMAC-SHA256）
    // ========================================================================

    /**
     * 计算签名：HMAC-SHA256(secret, paymentNo=..&orderNo=..&amount=..&channel=..)
     */
    private String sign(String paymentNo, String orderNo, BigDecimal amount, String channel) {
        String content = "paymentNo=" + paymentNo
                + "&orderNo=" + orderNo
                + "&amount=" + canonicalAmount(amount)
                + "&channel=" + channel;
        return hmacSha256Hex(signSecret, content);
    }

    /** 回调验签：用同样的规则重算并比对 */
    private boolean verify(PayNotifyReq req) {
        String content = "paymentNo=" + req.getPaymentNo()
                + "&orderNo=" + req.getOrderNo()
                // 金额两侧统一走 canonicalAmount() 归一化（去尾零）：
                // DB 里存 75.00（scale=2）→ "75.00"，前端/回调方传 75 或 75.0 → "75"，
                // 直接 toPlainString() 时 "75.00" ≠ "75" 会导致签名恒失败（2026-09-12 演示发现）。
                + "&amount=" + canonicalAmount(req.decimalAmount())
                + "&channel=" + req.getChannel();
        String expect = hmacSha256Hex(signSecret, content);
        return expect.equalsIgnoreCase(req.getSign());
    }

    /**
     * 金额签名规范化：去掉尾零，保证"75.00" / "75.0" / "75" 落到同一字符串 "75"，
     * 避免预付与回调因 scale 不同导致验签失败。
     */
    private String canonicalAmount(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }

    private static String hmacSha256Hex(String secret, String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                  .append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 签名计算失败", e);
        }
    }
}