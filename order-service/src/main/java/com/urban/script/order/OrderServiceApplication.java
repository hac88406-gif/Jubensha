package com.urban.script.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 订单微服务启动类
 *
 * <p>职责：预约下单 / Redis+Lua 原子扣位 / RabbitMQ 延迟关单 / Feign 调用 shop-service 查场次
 * <p>端口：8084
 *
 * <p>注意：order-service <b>不直接查询</b> shop-service 的表（session_info / script_info），
 * 而是通过 {@code feign/ShopClient} 调用 shop-service 暴露的内部接口 {@code /internal/session/{id}}，
 * 保持微服务间解耦。
 *
 * @author urban-script-reservation
 */
@SpringBootApplication(scanBasePackages = {"com.urban.script.order", "com.urban.script.common"})
@EnableDiscoveryClient
@EnableFeignClients
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
        System.out.println("\n====== order-service 已启动，端口 8084 ======\n");
    }
}
