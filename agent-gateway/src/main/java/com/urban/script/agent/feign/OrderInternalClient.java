package com.urban.script.agent.feign;

import com.urban.script.common.InternalApiKeyInterceptor;
import com.urban.script.common.R;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDateTime;
import java.util.List;

/**
 * order-service 内部接口 Feign 客户端 —— 供 agent-gateway 代理 Python Agent 调用
 *
 * <p>完整路径带 Controller base path /order：
 * <pre>
 *   order-service OrderController base path = /order
 *   新增内部接口: GET /order/internal/orderNo/{orderNo}、GET /order/internal/my
 * </pre>
 *
 * @author urban-script-reservation
 */
@FeignClient(
        name = "order-service",
        configuration = InternalApiKeyInterceptor.class,
        fallbackFactory = OrderInternalClient.OrderInternalFallback.class
)
public interface OrderInternalClient {

    /**
     * 按 orderNo 查订单详情（order-service: OrderController /order/internal/orderNo/{orderNo}）
     *
     * <p>透传 X-User-Id / X-User-Role 给 order-service：若调用方带了明确身份
     * 且不是店长，下游会强制校验"只能查本人订单"，防 AI 越权查他人订单。
     */
    @GetMapping("/order/internal/orderNo/{orderNo}")
    R<OrderRes> getOrder(@PathVariable("orderNo") String orderNo,
                         @RequestHeader(value = "X-User-Id", required = false) Long userId,
                         @RequestHeader(value = "X-User-Role", required = false) String role);

    /**
     * 按 X-User-Id 查用户所有订单（order-service: OrderController /order/internal/my）
     */
    @GetMapping("/order/internal/my")
    R<List<OrderRes>> myOrders(@RequestHeader("X-User-Id") Long userId);

    /**
     * 按 orderNo 取消订单（order-service: OrderController /order/internal/cancel）
     * <p>透传 X-User-Id 做归属校验：仅能取消本人且 status=0 待支付的订单（幂等）。
     */
    @PostMapping("/order/internal/cancel")
    R<Void> cancelOrder(@RequestParam("orderNo") String orderNo,
                        @RequestHeader(value = "X-User-Id", required = false) Long userId);

    // ========================================================================
    // DTO（与 order-service 的 OrderRes 字段对齐，取 agent 需要的子集）
    // ========================================================================

    @Data
    class OrderRes {
        private String orderNo;
        private Long sessionId;
        private Integer playerCnt;
        private Integer status;
        private String cancelReason;
        private LocalDateTime createTime;
        // 场次展示字段（order-service 已注入）
        private java.time.LocalDate sessionDate;
        private java.time.LocalTime startTime;
        private java.time.LocalTime endTime;
    }

    // ========================================================================
    // Fallback
    // ========================================================================

    @Slf4j
    @Component
    class OrderInternalFallback implements FallbackFactory<OrderInternalClient> {
        @Override
        public OrderInternalClient create(Throwable cause) {
            log.warn("[OrderInternalClientFallback] order-service Feign 调用失败: {}",
                    cause != null ? cause.getMessage() : "unknown");

            return new OrderInternalClient() {
                @Override
                public R<OrderRes> getOrder(String orderNo, Long userId, String role) {
                    return R.fail(503, "订单服务暂不可用，请稍后再试");
                }
                @Override
                public R<List<OrderRes>> myOrders(Long userId) {
                    return R.fail(503, "订单服务暂不可用，请稍后再试");
                }
                @Override
                public R<Void> cancelOrder(String orderNo, Long userId) {
                    return R.fail(503, "订单服务暂不可用，请稍后再试");
                }
            };
        }
    }
}
