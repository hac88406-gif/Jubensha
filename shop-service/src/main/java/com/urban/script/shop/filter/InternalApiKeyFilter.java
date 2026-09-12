package com.urban.script.shop.filter;

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
 * shop-service 内部接口鉴权 Filter —— 拦所有 /internal/** 路径
 *
 * <p>校验逻辑：
 * <pre>
 *   只匹配 /internal/** 前缀（Controller 里路径如 /session/internal/{id}、/script/internal/list）
 *   从 Header 取 X-Internal-Api-Key 与 urban.internal-api-key 比对
 *   不匹配 → 返回 403 JSON "服务间鉴权失败"
 * </pre>
 *
 * <p>密钥通过 {@link Environment} 每次实时读取，Nacos 改 urban.internal-api-key 后，
 * <b>无需重启服务</b>即可生效（Spring Cloud 会刷新 Environment 中的 PropertySource）。
 *
 * <p>与 reservation-common 的 InternalApiKeyInterceptor（Feign 调用时注入 Header）
 * 形成一对：Interceptor 负责"发出时带上"，Filter 负责"收到时校验"。
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

        // 只拦截 /internal/** 路径（Controller 里路径如 /session/internal/{id}、/script/internal/list）
        // 用 contains 比 startsWith 更宽松，支持任意 Controller 下的 /internal 子路径
        if (!requestURI.contains("/internal/")) {
            chain.doFilter(request, response);
            return;
        }

        // 每次都从 Environment 拿最新值（Nacos 热刷新无需重启）。
        // 密钥统一由配置提供（Nacos urban-shared-config > 环境变量 > 各服务 yml 默认值），
        // 源码不再内置硬编码兜底：拿不到就直接拒绝，避免"公开的默认值"变成绕过内部鉴权的通道。
        String expected = env.getProperty("urban.internal-api-key");
        if (expected == null || expected.isBlank()) {
            log.error("[InternalApiKeyFilter] ❌ shop-service 未配置 urban.internal-api-key，拒绝内部请求 URI={}",
                    requestURI);
            httpResponse.setStatus(HttpServletResponse.SC_FORBIDDEN);
            httpResponse.setContentType("application/json;charset=UTF-8");
            httpResponse.getWriter().write(objectMapper.writeValueAsString(
                    R.fail(403, "服务间鉴权未配置")));
            return;
        }
        String apiKey = httpRequest.getHeader("X-Internal-Api-Key");

        if (apiKey == null || !apiKey.equals(expected)) {
            log.warn("[InternalApiKeyFilter] ❌ shop-service 服务间鉴权失败 URI={}, client={}, header={}",
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
