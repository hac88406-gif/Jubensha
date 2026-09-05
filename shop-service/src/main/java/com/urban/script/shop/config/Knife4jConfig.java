package com.urban.script.shop.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Knife4j (OpenAPI 3) 配置
 *
 * <p>访问地址: http://localhost:8083/doc.html
 *
 * @author urban-script-reservation
 */
@Configuration
public class Knife4jConfig {

    @Bean
    public OpenAPI shopServiceOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("店铺/剧本服务 API")
                        .description("urban-script-reservation · shop-service (端口 8083)\n"
                                + "- 门店 CRUD (玩家端 + 店长管理端)\n"
                                + "- 剧本 CRUD (玩家端筛选 + 店长管理端)\n"
                                + "- 角色鉴权 @RequireRole")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("urban-script-reservation")
                                .email("dev@urban-script.com")));
    }
}
