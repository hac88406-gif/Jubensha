package com.urban.script.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 登录请求 DTO
 *
 * @author urban-script-reservation
 */
@Data
@Schema(description = "登录请求")
public class LoginReq {

    @NotBlank(message = "用户名不能为空")
    @Schema(description = "登录用户名", example = "alice", requiredMode = Schema.RequiredMode.REQUIRED)
    private String username;

    @NotBlank(message = "密码不能为空")
    @Schema(description = "明文密码", example = "123456", requiredMode = Schema.RequiredMode.REQUIRED)
    private String password;
}
