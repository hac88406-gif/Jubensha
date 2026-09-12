package com.urban.script.order.feign;

import com.urban.script.common.R;
import com.urban.script.common.InternalApiKeyInterceptor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * shop-service Feign 客户端
 *
 * <p>只调用 shop-service 暴露的内部接口（Gateway 白名单放行 /internal/** 路径）。
 * <p>所有请求自动带上 {@code X-Internal-Api-Key} Header，由 {@link InternalApiKeyInterceptor} 注入。
 *
 * <p>注意：order-service <b>不直接查 shop-service 的 MySQL 表</b>，
 * 全部场次 / 剧本信息都通过此 Feign Client 获取，保持微服务解耦。
 *
 * @author urban-script-reservation
 */
@FeignClient(
        name = "shop-service",
        // 注入 X-Internal-Api-Key 鉴权 Header
        configuration = InternalApiKeyInterceptor.class,
        // 降级：shop-service 挂了不影响 order-service 主链路。
        // 统一使用 FallbackFactory 模式（与 PythonAgentClient / ShopInternalClient 等
        // 其余 3 个 Feign 客户端保持一致），可拿到原始 Throwable 记录日志、区分降级原因
        fallbackFactory = ShopClient.ShopClientFallbackFactory.class
)
public interface ShopClient {

    /**
     * 查询场次核心信息（供 order-service 校验场次存在 / 容量 / 状态）
     *
     * @param sessionId 场次 ID
     * @return 场次精简 DTO；不存在时 code=404
     */
    @GetMapping("/session/internal/{sessionId}")
    R<SessionFeignRes> getSession(@PathVariable("sessionId") Long sessionId);

    // ========================================================================
    // Feign 响应 DTO（与 shop-service 的 SessionFeignRes 字段对齐）
    // ========================================================================

    /**
     * 场次 Feign 响应 DTO
     * <p>字段与 shop-service 的 {@code dto.SessionFeignRes} 完全一致，
     * OpenFeign 反序列化只认 JSON key，不依赖类位置。
     */
    @Data
    class SessionFeignRes {
        private Long id;
        private Long scriptId;
        private Long shopId;
        private Long dmId;
        private LocalDate sessionDate;
        private LocalTime startTime;
        private LocalTime endTime;
        /** 总容量 */
        private Integer capacity;
        /** 已预约 */
        private Integer booked;
        /** 剧本单价（订单服务计算支付金额用，与 shop-service SessionFeignRes 对齐） */
        private BigDecimal price;
        /** 场次状态：0=已关闭 1=开放 */
        private Integer status;

        public boolean isOpen() {
            return status != null && status == 1;
        }
    }

    /**
     * Feign 降级回退工厂（shop-service 不可用 / 熔断 / 超时时的兜底）
     * <p>
     * 使用 {@link FallbackFactory} 而非 {@code fallback = Xxx.class}：
     * <ul>
     *   <li>能拿到原始 {@link Throwable}，记录告警日志（旧式 fallback 静默降级，线上排障缺关键一环）</li>
     *   <li>可按异常类型细分提示（连接超时=稍后重试 / 业务 500=联系管理员 / 限流=请求过多）</li>
     *   <li>与项目内其余 3 个 Feign 客户端（PythonAgentClient / OrderInternalClient / ShopInternalClient）模式统一</li>
     * </ul>
     */
    @Slf4j
    @Component
    class ShopClientFallbackFactory implements FallbackFactory<ShopClient> {
        @Override
        public ShopClient create(Throwable cause) {
            // 告警日志带完整堆栈：shop-service 熔断/超时/连接拒绝时 order-service 侧不再零告警
            log.warn("[ShopClientFallback] shop-service Feign 调用失败，触发降级。原因: {}",
                    cause != null ? cause.getMessage() : "unknown", cause);

            return new ShopClient() {
                @Override
                public R<SessionFeignRes> getSession(Long sessionId) {
                    // 降级细分提示：cause 为 null 或非 Exception 时给通用文案
                    String reason = (cause != null && cause.getMessage() != null)
                            ? cause.getMessage() : "未知原因";
                    log.info("[ShopClientFallback] getSession 降级返回 sessionId={}, cause={}",
                            sessionId, reason);
                    return R.fail(503, "剧本店铺服务暂不可用，请稍后重试（降级原因：" + reason + "）");
                }
            };
        }
    }
}
