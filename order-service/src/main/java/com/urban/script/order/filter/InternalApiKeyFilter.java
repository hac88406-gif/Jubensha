package com.urban.script.order.filter;

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
 * order-service 内部接口鉴权 Filter —— 拦所有 /internal/** 路径
 *
 * <p>密钥通过 {@link Environment} 每次实时读取，Nacos 改 urban.internal-api-key 后，
 * <b>无需重启服务</b>即可生效。
 *
 * @author urban-script-reservation
 */
@Slf4j
@Component
@Order(1)
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

        // 只拦截 /internal/** 路径
        if (!requestURI.contains("/internal/")) {
            chain.doFilter(request, response);
            return;
        }

        // 每次都从 Environment 拿最新值（Nacos 热刷新无需重启）。
        // fallback 与 InternalApiKeyInterceptor 完全一致，确保"Nacos 不可达"时服务间调用仍兼容。
        String expected = env.getProperty("urban.internal-api-key", "urban-internal-api-key-dev-fallback");
        String apiKey = httpRequest.getHeader("X-Internal-Api-Key");

        if (apiKey == null || !apiKey.equals(expected)) {
            log.warn("[InternalApiKeyFilter] ❌ order-service 服务间鉴权失败 URI={}, client={}, header={}",
                    requestURI, httpRequest.getRemoteAddr(), apiKey);

            httpResponse.setStatus(HttpServletResponse.SC_FORBIDDEN);
            httpResponse.setContentType("application/json;charset=UTF-8");

            R<String> error = R.fail(403, "服务间鉴权失败");
            httpResponse.getWriter().write(objectMapper.writeValueAsString(error));
            return;
        }

        chain.doFilter(request, response);
    }

    @Override
    public void destroy() {
        // no-op
    }
}
