package com.urban.script.agent.feign;

import com.urban.script.common.InternalApiKeyInterceptor;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Python Agent Feign 客户端
 *
 * <p>Python Agent 是独立部署的 LangGraph 服务（端口 8000），
 * agent-gateway 通过 OpenFeign 直连（不走 Nacos 注册）。
 *
 * <p>Fallback 策略：Sentinel 集成 FallbackFactory
 * <pre>
 *   Sentinel 触发限流 / 异常 → FallbackFactory.create(Throwable) 被调用
 *   → 返回 ChatResponse.builder().code(200).message("智能客服暂时繁忙，请稍后再试").build()
 * </pre>
 *
 * @author urban-script-reservation
 */
@FeignClient(
        name = "python-agent",          // FeignClient 名称（用于日志/监控标识，不用做 Nacos 发现）
        url = "${agent.base-url}",      // 直连 Python Agent 基础 URL
        configuration = InternalApiKeyInterceptor.class,
        fallbackFactory = PythonAgentClientFallback.class
)
public interface PythonAgentClient {

    /**
     * 健康检查（Python Agent 启动时会暴露这个端点）
     */
    @GetMapping("/api/health")
    String healthCheck();

    /**
     * 发送对话请求给 Python Agent
     *
     * @param request 对话请求（含用户输入 + 会话历史）
     * @return Agent 回复
     */
    @PostMapping("/api/chat")
    ChatResponse chat(@RequestBody ChatRequest request);
}
