package com.urban.script.agent.feign;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * Python Agent Feign Fallback 工厂 —— Sentinel 集成用
 *
 * <p>当 Sentinel 触发限流 / 熔断，或 Feign 调用异常时，Spring Cloud OpenFeign
 * 会自动调用这个 {@link FallbackFactory} 的 {@code create(Throwable)} 方法，
 * 把原始异常传进来，返回一个兜底实现。
 *
 * <p>与直接用 {@code fallback = Xxx.class} 的区别：
 * <ul>
 *   <li>{@code FallbackFactory} 能拿到 <b>原始异常对象</b>，方便日志记录和区分降级原因</li>
 *   <li>支持<b>每个方法</b>独立降级逻辑（本例 chat 硬编码返回提示语）</li>
 * </ul>
 *
 * <p>P0 阶段先硬编码降级消息，后续可以根据异常类型细分：
 * <pre>
 *   FeignException.ConnectException → "智能客服服务未启动，请联系管理员"
 *   Sentinel BlockException         → "当前请求较多，请稍后再试"
 *   其他 Exception                   → "智能客服暂时繁忙，请稍后再试"
 * </pre>
 *
 * @author urban-script-reservation
 */
@Slf4j
@Component
public class PythonAgentClientFallback implements FallbackFactory<PythonAgentClient> {

    /** 兜底提示语 */
    private static final String FALLBACK_MSG = "智能客服暂时繁忙，请稍后再试";

    @Override
    public PythonAgentClient create(Throwable cause) {
        log.warn("[PythonAgentClientFallback] Feign 调用失败，触发 fallback。原因: {}",
                cause != null ? cause.getMessage() : "unknown", cause);

        return new PythonAgentClient() {
            @Override
            public String healthCheck() {
                return "fallback";
            }

            @Override
            public ChatResponse chat(ChatRequest request) {
                log.info("[PythonAgentClientFallback] chat fallback 返回硬编码消息 userId={}",
                        request != null ? request.getUserId() : null);
                return ChatResponse.fallback(FALLBACK_MSG);
            }
        };
    }
}
