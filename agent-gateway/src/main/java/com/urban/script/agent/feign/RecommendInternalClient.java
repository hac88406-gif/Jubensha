package com.urban.script.agent.feign;

import com.urban.script.common.InternalApiKeyInterceptor;
import com.urban.script.common.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * recommend-service 内部接口 Feign 客户端 —— 供 agent-gateway 代理 Python Agent 调用
 *
 * <p>完整路径带 Controller base path /recommend：
 * <pre>
 *   recommend-service RecommendController base path = /recommend
 *   公开接口:  GET /recommend/similar/{scriptId}   —— 相似剧本（Neo4j RELATED_TO）
 *   内部接口:  GET /recommend/internal/filter      —— 智能选本（Cypher 过滤，X-Internal-Api-Key 校验）
 * </pre>
 *
 * <p>similar 虽为公开接口，但 agent-gateway 通过 InternalApiKeyInterceptor 注入
 * X-Internal-Api-Key 后 Feign 直连 recommend-service，recommend-service 对公开接口不校验该 Key，
 * 链路无需额外鉴权。
 *
 * @author urban-script-reservation
 */
@FeignClient(
        name = "recommend-service",
        configuration = InternalApiKeyInterceptor.class,
        fallbackFactory = RecommendInternalClient.RecommendInternalFallback.class
)
public interface RecommendInternalClient {

    /**
     * 相似剧本（recommend-service: RecommendController /recommend/similar/{scriptId}）
     * <p>Neo4j RELATED_TO 关系召回，返回 [{scriptId, name, image, mark, scriptType, score}]。
     */
    @GetMapping("/recommend/similar/{scriptId}")
    R<List<Map<String, Object>>> similar(@PathVariable("scriptId") Long scriptId);

    /**
     * 智能选本（recommend-service: RecommendController /recommend/internal/filter）
     * <p>按 类型 / 细标签 / 期望人数 做 Cypher 过滤，返回 [{scriptId, name, scriptType, mark, tags, ...}]。
     */
    @GetMapping("/recommend/internal/filter")
    R<List<Map<String, Object>>> smartFilter(@RequestParam(required = false) String type,
                                             @RequestParam(required = false) String tag,
                                             @RequestParam(required = false) Integer playerCnt,
                                             @RequestParam(required = false) Integer limit);

    // ========================================================================
    // Fallback（recommend-service 挂了 / Sentinel 限流时兜底）
    // ========================================================================

    @Slf4j
    @Component
    class RecommendInternalFallback implements FallbackFactory<RecommendInternalClient> {
        @Override
        public RecommendInternalClient create(Throwable cause) {
            log.warn("[RecommendInternalClientFallback] recommend-service Feign 调用失败: {}",
                    cause != null ? cause.getMessage() : "unknown");

            return new RecommendInternalClient() {
                @Override
                public R<List<Map<String, Object>>> similar(Long scriptId) {
                    return R.fail(503, "推荐服务暂不可用，请稍后再试");
                }
                @Override
                public R<List<Map<String, Object>>> smartFilter(String type, String tag,
                                                                Integer playerCnt, Integer limit) {
                    return R.fail(503, "推荐服务暂不可用，请稍后再试");
                }
            };
        }
    }
}
