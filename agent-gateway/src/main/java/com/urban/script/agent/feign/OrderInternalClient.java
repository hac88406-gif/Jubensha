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
import org.springframework.web.bind.annotation.RequestHeader;

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
     */
    @GetMapping("/order/internal/orderNo/{orderNo}")
    R<OrderRes> getOrder(@PathVariable("orderNo") String orderNo);

    /**
     * 按 X-User-Id 查用户所有订单（order-service: OrderController /order/internal/my）
     */
    @GetMapping("/order/internal/my")
    R<List<OrderRes>> myOrders(@RequestHeader("X-User-Id") Long userId);

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
                public R<OrderRes> getOrder(String orderNo) {
                    return R.fail(503, "订单服务暂不可用，请稍后再试");
                }
                @Override
                public R<List<OrderRes>> myOrders(Long userId) {
                    return R.fail(503, "订单服务暂不可用，请稍后再试");
                }
            };
        }
    }
}
