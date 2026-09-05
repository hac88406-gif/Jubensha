package com.urban.script.shop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 店铺/剧本微服务启动类
 *
 * <p>职责：门店 CRUD / 剧本 CRUD / 场次管理（后续）
 * <p>端口：8083
 *
 * <p>scanBasePackages 显式包含 {@code com.urban.script.common}，
 * 因为 GlobalExceptionHandler / RequireRole / BusinessException 等公共组件
 * 在 reservation-common 模块里，属于兄弟包，Spring Boot 默认扫不到。
 *
 * @author urban-script-reservation
 */
@SpringBootApplication(scanBasePackages = {"com.urban.script.shop", "com.urban.script.common"})
@EnableDiscoveryClient
@EnableFeignClients
public class ShopServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShopServiceApplication.class, args);
        System.out.println("\n====== shop-service 已启动，端口 8083 ======\n");
    }
}
