package com.urban.script.recommend.filter;

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
 * recommend-service 内部接口鉴权 Filter —— 拦所有 /internal/** 路径
 * <p>
 * 与 shop-service / order-service 保持一致：
 * 校验 X-Internal-Api-Key Header，不匹配返回 403 "服务间鉴权失败"。
 * 密钥从 Environment 实时读取，支持 Nacos 热刷新。
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
    public void init(FilterConfig filterConfig) {
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String requestURI = httpRequest.getRequestURI();

        // 只拦截 /internal/** 路径（如 /recommend/internal/sync/script）
        if (!requestURI.contains("/internal/")) {
            chain.doFilter(request, response);
            return;
        }

        // 密钥统一由配置提供（Nacos urban-shared-config > 环境变量 > 各服务 yml 默认值），
        // 源码不再内置硬编码兜底：拿不到就直接拒绝，避免"公开的默认值"变成绕过内部鉴权的通道。
        String expected = env.getProperty("urban.internal-api-key");
        if (expected == null || expected.isBlank()) {
            log.error("[InternalApiKeyFilter] recommend-service 未配置 urban.internal-api-key，拒绝内部请求 URI={}",
                    requestURI);
            httpResponse.setStatus(HttpServletResponse.SC_FORBIDDEN);
            httpResponse.setContentType("application/json;charset=UTF-8");
            httpResponse.getWriter().write(objectMapper.writeValueAsString(
                    R.fail(403, "服务间鉴权未配置")));
            return;
        }
        String apiKey = httpRequest.getHeader("X-Internal-Api-Key");

        if (apiKey == null || !apiKey.equals(expected)) {
            log.warn("[InternalApiKeyFilter] recommend-service 鉴权失败 URI={}, header={}", requestURI, apiKey);
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
    }
}
