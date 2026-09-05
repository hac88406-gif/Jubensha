package com.urban.script.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * Gateway 跨域配置
 * <p>
 * Gateway 是 WebFlux 体系，需要用 <b>CorsWebFilter</b>（reactive 包），
 * 不能用 MVC 的 {@code WebMvcConfigurer#addCorsMappings}，否则不生效。
 * </p>
 * <p>
 * 生产环境建议将 allowedOrigins 替换为具体前端域名，allowedOriginPatterns 更灵活。
 * </p>
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration config = new CorsConfiguration();

        // 允许的来源：白名单域名（禁止 *，避免 allowCredentials(true) 时任意第三方网页
        // 携带玩家 Cookie/JWT 跨域调用预约接口，放大 CSRF 风险）。
        // 本项目前端端口约定：5174 / 5175（5173 为旧项目 green_chain 占用）。
        // 生产部署时替换为正式域名（如 https://www.example.com）。
        config.addAllowedOrigin("http://localhost:5174");
        config.addAllowedOrigin("http://127.0.0.1:5174");
        config.addAllowedOrigin("http://localhost:5175");
        config.addAllowedOrigin("http://127.0.0.1:5175");

        // 允许的 HTTP 方法
        config.addAllowedMethod("*");

        // 允许的请求头（重要：Gateway 转发时下游加的 X-User-Id 等 Header 需要透传）
        config.addAllowedHeader("*");

        // 暴露给前端的响应头
        config.addExposedHeader("*");

        // 允许携带凭证（Cookie / Authorization）
        config.setAllowCredentials(true);

        // 预检请求有效期（秒）
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // 对所有路径生效
        source.registerCorsConfiguration("/**", config);

        return new CorsWebFilter(source);
    }
}
