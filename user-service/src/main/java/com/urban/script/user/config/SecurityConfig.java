package com.urban.script.user.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 安全组件配置
 *
 * <p>本项目只引入了 {@code spring-security-crypto}（PasswordEncoder 系列），
 * 没有引入完整的 {@code spring-boot-starter-security}，所以不会自动启用 Spring Security
 * 的过滤器链，也就不会有默认的 CSRF / 登录页 / 403 问题 —— 非常适合微服务内部。
 *
 * @author urban-script-reservation
 */
@Configuration
public class SecurityConfig {

    /**
     * BCrypt 密码编码器（强度 10）。
     * 单 Bean 暴露即可，Service 层直接注入。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
