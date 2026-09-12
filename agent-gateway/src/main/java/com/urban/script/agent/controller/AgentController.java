package com.urban.script.agent.controller;

import com.urban.script.agent.feign.ChatRequest;
import com.urban.script.agent.feign.ChatResponse;
import com.urban.script.agent.feign.OrderInternalClient;
import com.urban.script.agent.feign.PythonAgentClient;
import com.urban.script.agent.feign.RecommendInternalClient;
import com.urban.script.agent.feign.ShopInternalClient;
import com.urban.script.common.R;
import com.urban.script.common.annotation.RequireRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Agent 中间层 Controller
 *
 * <p>路由分为三组：
 * <ul>
 *   <li>玩家端 AI 对话：POST /agent/chat → 透传给 Python Agent（Gateway 路由到 /api/agent/chat）</li>
 *   <li>内部接口：GET /agent/internal/order/{orderNo}、/agent/internal/script/list —— 给 Python Agent 查订单/剧本</li>
 *   <li>健康检查：GET /agent/health —— Python Agent 探活用</li>
 * </ul>
 *
 * <p>内部接口的鉴权由 {@link com.urban.script.agent.filter.InternalApiKeyFilter} 统一拦截，
 * Controller 层不再重复处理。
 *
 * @author urban-script-reservation
 */
@Slf4j
@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
@Tag(name = "Agent 中间层", description = "AI 对话 + 内部订单/剧本查询")
public class AgentController {

    private final PythonAgentClient pythonAgentClient;
    private final ShopInternalClient shopInternalClient;
    private final OrderInternalClient orderInternalClient;
    private final RecommendInternalClient recommendInternalClient;

    // ========================================================================
    // 玩家端
    // ========================================================================

    /**
     * AI 对话（玩家端入口）
     *
     * <p>通过 Gateway /api/agent/chat 路由到此，再 OpenFeign 调 Python Agent 的 /api/chat。
     * <p>双重兜底：Controller 层 try-catch + FallbackFactory（有 Sentinel 时生效）
     */
    @Operation(summary = "AI 对话（玩家端）")
    // 非管理端角色（玩家 / DM 等）均可使用 AI 陪练；DM 已并入玩家端，无需单独开放
    @RequireRole({"ROLE_PLAYER", "ROLE_DM"})
    @PostMapping("/chat")
    public R<ChatResponse> chat(@Valid @RequestBody ChatRequest request,
                                @RequestHeader(value = "X-User-Id", required = false) Long userId,
                                @RequestHeader(value = "X-User-Role", required = false) String role) {
        // 兜底：如果请求里没带 userId，用 Header 里的
        if (request.getUserId() == null && userId != null) {
            request.setUserId(userId);
        }

        log.info("[AgentController] /agent/chat userId={}, message={}", userId,
                request.getMessage() != null ? request.getMessage().substring(0, Math.min(request.getMessage().length(), 50)) : "");

        try {
            ChatResponse response = pythonAgentClient.chat(request);
            log.info("[AgentController] /agent/chat response code={}", response != null ? response.getCode() : "null");

            // Python 侧业务失败是 HTTP 200 + body code=500（LLM 调不通、工具调用异常等）。
            // 必须在这里转成 R.fail —— 否则 Feign 不抛异常，前端会把"AI 失败"当成成功处理。
            if (response != null && response.getCode() != null && response.getCode() != 200) {
                log.warn("[AgentController] python-agent 返回业务错误 code={}, message={}",
                        response.getCode(), response.getMessage());
                return R.fail(response.getCode(),
                        response.getMessage() != null ? response.getMessage() : "AI 服务异常");
            }
            return R.ok(response);
        } catch (Exception e) {
            // Sentinel 未启用时 FallbackFactory 不触发，Controller 层兜底
            log.warn("[AgentController] python-agent Feign 调用失败，返回兜底消息: {}", e.getMessage());
            return R.ok(ChatResponse.fallback("智能客服暂时繁忙，请稍后再试"));
        }
    }

    // ========================================================================
    // 内部接口（仅供 Python Agent / Java 微服务内部调用，带 X-Internal-Api-Key）
    // 鉴权由 InternalApiKeyFilter 统一拦截 /agent/internal/** 路径
    // ========================================================================

    /**
     * 根据 orderNo 查订单详情（Python Agent 用）
     * <p>OpenFeign 调用 order-service 的 /order/internal/orderNo/{orderNo}
     */
    @Operation(summary = "内部：根据 orderNo 查订单", hidden = true)
    @GetMapping("/internal/order/{orderNo}")
    public R<OrderInternalClient.OrderRes> getOrder(
            @PathVariable String orderNo,
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey) {
        log.info("[AgentController] → order-service.getOrder orderNo={}, userId={}", orderNo, userId);
        // 透传 X-User-Id/X-User-Role 给 order-service 做"只能查本人订单"的归属校验
        return orderInternalClient.getOrder(orderNo, userId, role);
    }

    /**
     * 查用户所有订单（Python Agent 用）
     * <p>OpenFeign 调用 order-service 的 /order/internal/my，X-User-Id 透传给下游
     */
    @Operation(summary = "内部：按用户查订单列表", hidden = true)
    @GetMapping("/internal/order/my")
    public R<List<OrderInternalClient.OrderRes>> myOrders(
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey) {
        log.info("[AgentController] → order-service.myOrders userId={}", userId);
        return orderInternalClient.myOrders(userId);
    }

    /**
     * 查剧本列表（Python Agent 用）
     * <p>OpenFeign 调用 shop-service 的 /script/internal/list
     */
    @Operation(summary = "内部：查剧本列表", hidden = true)
    @GetMapping("/internal/script/list")
    public R<List<ShopInternalClient.ScriptRes>> listScripts(
            @RequestParam(required = false) Long shopId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer playerCnt,
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey) {
        log.info("[AgentController] → shop-service.listScripts shopId={}, type={}, playerCnt={}",
                shopId, type, playerCnt);
        return shopInternalClient.listScripts(shopId, type, playerCnt);
    }

    /**
     * 查剧本详情（Python Agent 用）—— 剧情问答 / 角色介绍工具的数据源
     * <p>OpenFeign 调用 shop-service 的 /script/internal/{id}
     */
    @Operation(summary = "内部：查剧本详情", hidden = true)
    @GetMapping("/internal/script/{id}")
    public R<ShopInternalClient.ScriptRes> getScript(
            @PathVariable Long id,
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey) {
        log.info("[AgentController] → shop-service.getScript id={}", id);
        return shopInternalClient.getScript(id);
    }

    /**
     * 相似剧本（Python Agent 用）—— Neo4j 关系查询"和某本类似的"
     * <p>OpenFeign 调用 recommend-service 的 /recommend/similar/{scriptId}
     */
    @Operation(summary = "内部：相似剧本（Neo4j 关系查询）", hidden = true)
    @GetMapping("/internal/recommend/similar/{scriptId}")
    public R<List<Map<String, Object>>> similarScripts(
            @PathVariable Long scriptId,
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey) {
        log.info("[AgentController] → recommend-service.similar scriptId={}", scriptId);
        return recommendInternalClient.similar(scriptId);
    }

    /**
     * 智能选本（Python Agent 用）—— Cypher 过滤挑本
     * <p>OpenFeign 调用 recommend-service 的 /recommend/internal/filter
     */
    @Operation(summary = "内部：智能选本（Cypher 过滤）", hidden = true)
    @GetMapping("/internal/recommend/filter")
    public R<List<Map<String, Object>>> smartFilter(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) Integer playerCnt,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey) {
        log.info("[AgentController] → recommend-service.smartFilter type={}, tag={}, playerCnt={}",
                type, tag, playerCnt);
        return recommendInternalClient.smartFilter(type, tag, playerCnt, limit);
    }

    /**
     * 取消订单（Python Agent 用）
     * <p>OpenFeign 调用 order-service 的 /order/internal/cancel，X-User-Id 透传给下游做归属校验
     */
    @Operation(summary = "内部：按 orderNo 取消订单", hidden = true)
    @PostMapping("/internal/order/{orderNo}/cancel")
    public R<Void> cancelOrder(
            @PathVariable String orderNo,
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey) {
        log.info("[AgentController] → order-service.cancelOrder orderNo={}, userId={}", orderNo, userId);
        return orderInternalClient.cancelOrder(orderNo, userId);
    }

    // ========================================================================
    // 健康检查
    // ========================================================================

    /**
     * agent-gateway 自身健康检查（Gateway / Nacos 探活用）
     */
    @Operation(summary = "agent-gateway 健康检查")
    @GetMapping("/health")
    public R<String> health() {
        return R.ok("agent-gateway UP (port 8085)");
    }
}
