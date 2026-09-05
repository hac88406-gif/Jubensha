package com.urban.script.agent.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urban.script.common.R;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 服务间内部接口鉴权 Filter —— 拦 /agent/internal/** 路径
 *
 * <p>agent-gateway 的内部接口（/agent/internal/**）专供 Python Agent / Java 微服务内部调用，
 * 不需要 Gateway JWT 鉴权，但必须携带正确的 X-Internal-Api-Key Header。
 *
 * <p>密钥通过 {@link Environment} 每次实时读取，Nacos 改 urban.internal-api-key 后，
 * <b>无需重启服务</b>即可生效。
 *
 * <p>与 reservation-common 的 {@code InternalApiKeyInterceptor} 区别：
 * <ul>
 *   <li>InternalApiKeyInterceptor：Feign 调用时<b>注入</b> X-Internal-Api-Key Header</li>
 *   <li>InternalApiKeyFilter：被调用方<b>校验</b> X-Internal-Api-Key Header</li>
 * </ul>
 *
 * @author urban-script-reservation
 */
@Slf4j
@Component
@Order(1)  // 最先执行
public class InternalApiKeyFilter implements Filter {

    private final ObjectMapper objectMapper;
    private final Environment env;

    public InternalApiKeyFilter(ObjectMapper objectMapper, Environment env) {
        this.objectMapper = objectMapper;
        this.env = env;
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        // no-op
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String requestURI = httpRequest.getRequestURI();

        // 只拦截 /agent/internal/** 路径
        if (!requestURI.startsWith("/agent/internal/")) {
            chain.doFilter(request, response);
            return;
        }

        // 每次都从 Environment 拿最新值（Nacos 热刷新无需重启）。
        // fallback 与 InternalApiKeyInterceptor 完全一致，确保"Nacos 不可达"时服务间调用仍兼容。
        String expected = env.getProperty("urban.internal-api-key", "urban-internal-api-key-dev-fallback");
        String apiKey = httpRequest.getHeader("X-Internal-Api-Key");

        if (apiKey == null || !apiKey.equals(expected)) {
            log.warn("[InternalApiKeyFilter] ❌ 服务间鉴权失败 URI={}, client={}, header={}",
                    requestURI, httpRequest.getRemoteAddr(), apiKey);

            httpResponse.setStatus(HttpServletResponse.SC_FORBIDDEN);
            httpResponse.setContentType("application/json;charset=UTF-8");

            R<String> error = R.fail(403, "服务间鉴权失败");
            httpResponse.getWriter().write(objectMapper.writeValueAsString(error));
            return;
        }

        // 鉴权通过，继续
        chain.doFilter(request, response);
    }

    @Override
    public void destroy() {
        // no-op
    }
}
