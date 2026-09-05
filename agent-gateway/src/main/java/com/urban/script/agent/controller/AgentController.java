package com.urban.script.agent.controller;

import com.urban.script.agent.feign.ChatRequest;
import com.urban.script.agent.feign.ChatResponse;
import com.urban.script.agent.feign.OrderInternalClient;
import com.urban.script.agent.feign.PythonAgentClient;
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
    @RequireRole("ROLE_PLAYER")
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
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey) {
        log.info("[AgentController] → order-service.getOrder orderNo={}", orderNo);
        return orderInternalClient.getOrder(orderNo);
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
