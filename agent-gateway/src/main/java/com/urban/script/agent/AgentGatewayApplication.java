package com.urban.script.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * Agent 中间层网关启动类
 *
 * <p>职责：作为 Python Agent 与 Java 微服务之间的桥接层
 * <ul>
 *   <li>向上（Gateway）：暴露 /agent/** 路由，承接玩家 AI 对话请求</li>
 *   <li>向下（Python Agent）：OpenFeign 调用 Python Agent 的 /api/chat</li>
 *   <li>内部（Java 微服务）：Feign 代理 order-service / shop-service 内部接口</li>
 * </ul>
 *
 * <p>端口：8085
 * <p>技术栈：Servlet 栈 + Sentinel 1.8.6（与 reservation-gateway 的 WebFlux 栈不同）
 *
 * @author urban-script-reservation
 */
@SpringBootApplication(scanBasePackages = {"com.urban.script.agent", "com.urban.script.common"})
@EnableDiscoveryClient
@EnableFeignClients
public class AgentGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentGatewayApplication.class, args);
        System.out.println("\n====== agent-gateway 已启动，端口 8085 ======\n");
    }
}
