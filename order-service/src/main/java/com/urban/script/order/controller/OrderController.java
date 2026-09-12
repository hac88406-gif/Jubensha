package com.urban.script.order.controller;

import com.urban.script.common.BusinessException;
import com.urban.script.common.R;
import com.urban.script.common.ResultCode;
import com.urban.script.common.annotation.RequireRole;
import com.urban.script.order.dto.OrderCreateReq;
import com.urban.script.order.dto.OrderRes;
import com.urban.script.order.entity.OrderInfo;
import com.urban.script.order.feign.ShopClient;
import com.urban.script.order.mapper.OrderMapper;
import com.urban.script.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 订单 Controller
 *
 * <p>路由：
 * <ul>
 *   <li>玩家端：创建订单 / 我的订单列表 / 订单详情 / 取消订单</li>
 *   <li>内部接口：/internal/order/{id} —— 给 shop-service Feign 查订单状态用</li>
 * </ul>
 *
 * @author urban-script-reservation
 */
@Tag(name = "订单管理", description = "预约下单 / 订单列表 / 详情 / 取消")
@RestController
@RequestMapping("/order")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final OrderMapper orderMapper;
    private final ShopClient shopClient;

    // ===================== 玩家端 =====================

    @Operation(summary = "创建预约订单（玩家）")
    @RequireRole("ROLE_PLAYER")
    @PostMapping
    public R<String> create(@Valid @RequestBody OrderCreateReq req,
                            @RequestHeader(value = "X-User-Id", required = false) Long userId,
                            @RequestHeader(value = "X-User-Role", required = false) String role) {
        String orderNo = orderService.createOrder(userId, req);
        return R.ok("下单成功", orderNo);
    }

    @Operation(summary = "我的订单列表（玩家）")
    @RequireRole("ROLE_PLAYER")
    @GetMapping("/mine")
    public R<List<OrderRes>> mine(@RequestHeader(value = "X-User-Id", required = false) Long userId,
                                  @RequestHeader(value = "X-User-Role", required = false) String role) {
        List<OrderRes> list = orderService.listOrdersByUserId(userId);
        return R.ok(list);
    }

    @Operation(summary = "订单详情（玩家/店长）")
    @RequireRole({"ROLE_PLAYER", "ROLE_SHOP_OWNER"})
    @GetMapping("/{id}")
    public R<OrderRes> get(@PathVariable Long id,
                           @RequestHeader(value = "X-User-Id", required = false) Long userId,
                           @RequestHeader(value = "X-User-Role", required = false) String role) {
        // 带归属校验：玩家只能看自己的订单，店长/管理员可看任意订单
        return R.ok(orderService.getOrderDetailForUser(id, userId, role));
    }

    @Operation(summary = "取消订单（玩家主动取消 / 店长可取消自己店铺场次的订单）")
    @RequireRole({"ROLE_PLAYER", "ROLE_SHOP_OWNER"})
    @DeleteMapping("/{id}")
    public R<Void> cancel(@PathVariable Long id,
                          @RequestHeader(value = "X-User-Id", required = false) Long userId,
                          @RequestHeader(value = "X-User-Role", required = false) String role) {
        // 带归属校验：玩家只能取消自己的订单（防恶意取消他人订单触发库存回滚）
        orderService.cancelOrderForUser(id, userId, role, "USER_CANCEL", role + ":" + userId);
        return R.ok("取消成功", null);
    }

    // ===================== 内部 Feign 接口 =====================
    // 鉴权由 InternalApiKeyFilter 统一拦截 /internal/** 路径
    // 供 shop-service Feign 查订单状态用 + agent-gateway 代理 Python Agent 查询

    /** 内部：按主键 ID 查订单（shop-service 用，保持不变） */
    @Operation(summary = "订单详情（内部 Feign，供 shop-service 查询）", hidden = true)
    @GetMapping("/internal/{id}")
    public R<OrderRes> getForFeign(@PathVariable Long id,
                                   @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey) {
        return R.ok(orderService.getOrderDetail(id));
    }

    /** 内部：按 orderNo 查订单（agent-gateway 代理 Python Agent 用，带可选归属校验） */
    @Operation(summary = "订单详情（内部 Feign，按 orderNo 查）", hidden = true)
    @GetMapping("/internal/orderNo/{orderNo}")
    public R<OrderRes> getByOrderNo(@PathVariable String orderNo,
                                    @RequestHeader(value = "X-User-Id", required = false) Long userId,
                                    @RequestHeader(value = "X-User-Role", required = false) String role,
                                    @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey) {
        OrderInfo order = orderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "订单不存在");
        }
        // AI 通道归属校验：若调用方带上了明确身份且不是店长，只允许查本人订单
        // （Python order_tool.query_order 带上 X-User-Id 后，可防止"AI 帮任意人查任意单"）
        boolean staff = "ROLE_SHOP_OWNER".equalsIgnoreCase(role);
        if (userId != null && !staff && !order.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "无权查看他人的订单");
        }
        ShopClient.SessionFeignRes session = shopClient.getSession(order.getSessionId()).getData();
        return R.ok(OrderRes.fromEntityWithSession(order, session));
    }

    /** 内部：按 X-User-Id 查用户所有订单（agent-gateway 代理 Python Agent 用） */
    @Operation(summary = "用户订单列表（内部 Feign，按 X-User-Id 查）", hidden = true)
    @GetMapping("/internal/my")
    public R<List<OrderRes>> internalMy(@RequestHeader(value = "X-User-Id", required = false) Long userId,
                                        @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey) {
        List<OrderRes> list = orderService.listOrdersByUserId(userId);
        return R.ok(list);
    }

    /** 内部：按 orderNo 取消订单（agent-gateway 代理 Python Agent 用，带归属校验） */
    @Operation(summary = "取消订单（内部 Feign，按 orderNo 取消，AI 用）", hidden = true)
    @PostMapping("/internal/cancel")
    public R<Void> internalCancel(@RequestParam String orderNo,
                                  @RequestHeader(value = "X-User-Id", required = false) Long userId,
                                  @RequestHeader(value = "X-User-Role", required = false) String role,
                                  @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey) {
        OrderInfo order = orderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "订单不存在");
        }
        // 复用玩家取消的归属校验 + 幂等取消逻辑（仅 status=0 待支付可取消，其余状态静默跳过）
        orderService.cancelOrderForUser(order.getId(), userId, role, "USER_CANCEL", "agent:" + userId);
        return R.ok("取消成功", null);
    }
}
