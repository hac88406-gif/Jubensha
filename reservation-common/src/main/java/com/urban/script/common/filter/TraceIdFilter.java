package com.urban.script.common.filter;

import com.urban.script.common.TraceIdUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 入站 request 的 TraceId 过滤器（Servlet 服务专用）
 *
 * <p>职责：
 * <ol>
 *   <li>读取上游透传的 {@code X-Trace-Id}（Gateway 已生成）；没有则本地生成，保证"一次请求必有 traceId"；</li>
 *   <li>通过 {@link TraceIdUtil} 绑定到 MDC，让本服务日志 pattern 输出 {@code [traceId]}；</li>
 *   <li>把 traceId 写回响应头 {@code X-Trace-Id}，方便 Postman / 前端 / 压测脚本直接取用；</li>
 *   <li>finally 中 {@link TraceIdUtil#clear()}，防止 Tomcat 线程池复用导致 traceId 串流。</li>
 * </ol>
 *
 * <p><b>双条件注解防污染网关</b>：
 * reservation-common 被 4 个 Servlet 服务（user/shop/order/agent-gateway）和
 * reservation-gateway（纯 WebFlux）同时加载。@ConditionalOnWebApplication(SERVLET)
 * 保证该 Filter 只在 Servlet 容器中注册，WebFlux 网关不会装配（网关有自己的 GlobalFilter）。
 *
 * @author urban-script-reservation
 */
@Slf4j
@Component
@ConditionalOnClass(name = "jakarta.servlet.Filter")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class TraceIdFilter extends OncePerRequestFilter {

    /** 恶意/异常超长 traceId 直接丢弃重建，防止日志注入或异常输入混入链路 */
    private static final int MAX_TRACE_ID_LEN = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String traceId = request.getHeader(TraceIdUtil.TRACE_ID_HEADER);
            if (traceId == null || traceId.isBlank() || traceId.length() > MAX_TRACE_ID_LEN) {
                // 无上游 traceId（直连服务/内部 MQ/超长注入）→ 本地生成
                traceId = TraceIdUtil.generate();
            }
            TraceIdUtil.set(traceId);

            // 回传响应头：同一链路上的任意一跳，调用方都能从响应里拿到本次 traceId
            response.setHeader(TraceIdUtil.TRACE_ID_HEADER, traceId);

            filterChain.doFilter(request, response);
        } finally {
            // 关键：Servlet 线程池复用，不清理会把本请求 traceId 带进下一个请求
            TraceIdUtil.clear();
        }
    }
}