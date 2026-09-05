package com.urban.script.order.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Knife4j / OpenAPI 配置
 *
 * @author urban-script-reservation
 */
@Configuration
public class Knife4jConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("order-service 订单微服务")
                .version("1.0.0")
                .description("预约下单 / Redis+Lua 原子扣位 / RabbitMQ 延迟关单"));
    }
}
