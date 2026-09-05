package com.urban.script.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * API 网关启动类
 * <p>
 * Gateway 作为微服务体系的"统一入口"：
 *   ① 注册到 Nacos，从 Nacos 发现 user / shop / order / agent-gateway 等服务实例
 *   ② 路由转发（lb://xxx-service 实现负载均衡）
 *   ③ JWT 鉴权（GlobalFilter 层）
 *   ④ Sentinel 限流熔断
 *   ⑤ 跨域处理
 * </p>
 * <p>
 * 注意：Gateway 是 <b>WebFlux</b> 应用，**绝对不能**同时引入 spring-boot-starter-web（MVC）。
 * <p>
 * 为什么要显式 scanBasePackages？
 *   JwtAutoConfiguration / JwtUtil / GlobalExceptionHandler 等公共组件在 reservation-common 里
 *   （包名 com.urban.script.common.*），与启动类所在的 com.urban.script.gateway 是兄弟包，
 *   Spring Boot 默认只扫自身子包，所以必须显式把 common 包加入扫描范围。
 * </p>
 */
@SpringBootApplication(scanBasePackages = {"com.urban.script.gateway", "com.urban.script.common"})
@EnableDiscoveryClient
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
        System.out.println("\n====== reservation-gateway 已启动，端口 8081，等待业务服务注册到 Nacos ... ======\n");
    }
}
