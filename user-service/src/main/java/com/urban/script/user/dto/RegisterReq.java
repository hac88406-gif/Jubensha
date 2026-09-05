package com.urban.script.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求 DTO
 *
 * @author urban-script-reservation
 */
@Data
@Schema(description = "注册请求")
public class RegisterReq {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 2, max = 20, message = "用户名长度 2-20 个字符")
    @Schema(description = "登录用户名", example = "alice", requiredMode = Schema.RequiredMode.REQUIRED)
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 32, message = "密码长度 6-32 个字符")
    @Schema(description = "明文密码（服务端会 BCrypt 加密）", example = "123456", requiredMode = Schema.RequiredMode.REQUIRED)
    private String password;

    /**
     * 手机号（可选，格式：1 开头 11 位数字）
     */
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    @Schema(description = "手机号（可选）", example = "13800138000")
    private String phone;
}
