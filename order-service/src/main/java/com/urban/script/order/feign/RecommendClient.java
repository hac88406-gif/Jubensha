package com.urban.script.order.feign;

import com.urban.script.common.R;
import com.urban.script.common.InternalApiKeyInterceptor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * recommend-service Feign 客户端
 * <p>
 * 支付成功后同步「玩家-剧本」游玩关系到 Neo4j，用于协同过滤推荐。
 * <p>
 * 使用直连 URL（http://127.0.0.1:8086）绕过 Nacos，与 ShopClient 的服务发现模式互补。
 * 所有请求自动带 X-Internal-Api-Key（由 InternalApiKeyInterceptor 注入）。
 */
@FeignClient(
        name = "recommend-service",
        url = "http://127.0.0.1:8086",
        configuration = InternalApiKeyInterceptor.class,
        fallbackFactory = RecommendClient.RecommendClientFallbackFactory.class
)
public interface RecommendClient {

    /**
     * 同步订单到 Neo4j（玩家-剧本 PLAYED 关系）
     */
    @PostMapping("/recommend/internal/sync/order")
    R<Void> syncOrder(@RequestBody OrderSyncReq req);

    // ========================================================================
    // Feign 请求 DTO
    // ========================================================================

    @Data
    class OrderSyncReq {
        private Long userId;
        private String username;
        private Long scriptId;
    }

    // ========================================================================
    // 降级回退
    // ========================================================================

    @Slf4j
    @Component
    class RecommendClientFallbackFactory implements FallbackFactory<RecommendClient> {
        @Override
        public RecommendClient create(Throwable cause) {
            log.warn("[RecommendClientFallback] recommend-service 调用失败，降级跳过。原因: {}",
                    cause != null ? cause.getMessage() : "unknown", cause);
            return req -> {
                log.info("[RecommendClientFallback] syncOrder 降级返回，userId={}, scriptId={}",
                        req.getUserId(), req.getScriptId());
                return R.fail(503, "推荐服务暂不可用，跳过图谱同步");
            };
        }
    }
}
