package com.urban.script.user.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Knife4j (OpenAPI 3) 配置
 *
 * <p>访问地址: http://localhost:8082/doc.html
 *
 * @author urban-script-reservation
 */
@Configuration
public class Knife4jConfig {

    /**
     * 定义全局 OpenAPI 元信息（标题 / 版本 / 描述 / 作者）。
     * Knife4j 会自动扫描 Controller 下带 @Operation 注解的接口生成在线文档。
     */
    @Bean
    public OpenAPI userServiceOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("用户服务 API")
                        .description("urban-script-reservation · user-service (端口 8082)\n"
                                + "- 注册 / 登录 / JWT 签发\n"
                                + "- 角色鉴权 @RequireRole\n"
                                + "- 雪花 ID 生成器")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("urban-script-reservation")
                                .email("dev@urban-script.com")));
    }
}
