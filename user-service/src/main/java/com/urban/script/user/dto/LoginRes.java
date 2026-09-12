package com.urban.script.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录响应 DTO
 *
 * @author urban-script-reservation
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "登录成功响应")
public class LoginRes {

    @Schema(description = "JWT Token（Gateway 后续从 Authorization: Bearer {token} 提取）",
            example = "eyJhbGciOiJIUzI1NiJ9...")
    private String token;

    @Schema(description = "用户 ID", example = "1")
    private Long userId;

    /**
     * 角色字符串，格式 "ROLE_PLAYER" / "ROLE_DM" / "ROLE_SHOP_OWNER"
     * <p>Gateway 从 JWT 解析 role claim 得到，传给下游服务做 RBAC
     */
    @Schema(description = "角色编码（如 ROLE_PLAYER）", example = "ROLE_PLAYER")
    private String role;
}
