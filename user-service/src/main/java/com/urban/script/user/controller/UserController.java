package com.urban.script.user.controller;

import com.urban.script.common.R;
import com.urban.script.common.annotation.RequireRole;
import com.urban.script.user.dto.LoginReq;
import com.urban.script.user.dto.LoginRes;
import com.urban.script.user.dto.RegisterReq;
import com.urban.script.user.dto.UserProfileRes;
import com.urban.script.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户 Controller
 *
 * <p>
 * Gateway 未启动时可以直接访问 http://localhost:8082/user/**
 * Gateway 启动后访问 http://localhost:8081/api/user/**（Gateway 配置了 StripPrefix=2）
 * </p>
 *
 * @author urban-script-reservation
 */
@Tag(name = "用户服务", description = "注册 / 登录 / 个人信息")
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * 用户注册
     */
    @Operation(summary = "用户注册")
    @PostMapping("/register")
    public R<Long> register(@Valid @RequestBody RegisterReq req) {
        Long userId = userService.register(req);
        return R.ok("注册成功", userId);
    }

    /**
     * 用户登录
     */
    @Operation(summary = "用户登录（返回 JWT）")
    @PostMapping("/login")
    public R<LoginRes> login(@Valid @RequestBody LoginReq req) {
        return R.ok(userService.login(req));
    }

    /**
     * 个人信息（登录即可，所有角色都允许访问）
     * <p>
     * Gateway 会从 JWT 里解析 userId / role 后注入到请求头：
     *   X-User-Id   → subject
     *   X-User-Role → role claim
     * 直连 user-service 调试时需要手动带这两个 Header
     * </p>
     */
    @Operation(summary = "获取用户个人信息（需登录）")
    @RequireRole(value = {"ROLE_PLAYER", "ROLE_DM", "ROLE_SHOP_OWNER", "ROLE_ADMIN"})
    @GetMapping("/profile")
    public R<UserProfileRes> profile(
            @Parameter(hidden = true)
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @Parameter(hidden = true)
            @RequestHeader(value = "X-User-Role", required = false) String role) {
        return R.ok(userService.getProfile(userId));
    }
}
