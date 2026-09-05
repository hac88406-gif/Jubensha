package com.urban.script.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户个人信息响应 DTO（脱敏，不含 password）
 *
 * @author urban-script-reservation
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "用户个人信息（脱敏）")
public class UserProfileRes {

    @Schema(description = "用户 ID")
    private Long id;

    @Schema(description = "登录用户名")
    private String username;

    @Schema(description = "手机号（脱敏后：138****8000）")
    private String phone;

    @Schema(description = "头像 URL")
    private String avatar;

    @Schema(description = "角色编码（如 ROLE_PLAYER）")
    private String role;

    @Schema(description = "账号状态：0=禁用 1=正常")
    private Integer status;

    @Schema(description = "注册时间")
    private LocalDateTime createTime;
}
