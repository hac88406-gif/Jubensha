package com.urban.script.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 用户微服务启动类
 *
 * <p>职责：注册 / 登录 / JWT 签发 / 角色鉴权
 * <p>端口：8082
 *
 * <p>scanBasePackages 必须显式包含 {@code com.urban.script.common}，
 * 因为 GlobalExceptionHandler / JwtUtil 等公共组件在 common 模块里，
 * 属于兄弟包，Spring Boot 默认扫不到。
 *
 * @author urban-script-reservation
 */
@SpringBootApplication(scanBasePackages = {"com.urban.script.user", "com.urban.script.common"})
@EnableDiscoveryClient
public class UserServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
        System.out.println("\n====== user-service 已启动，端口 8082 ======\n");
    }
}
