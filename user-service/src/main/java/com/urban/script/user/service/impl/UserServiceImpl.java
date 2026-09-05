package com.urban.script.user.service.impl;

import com.urban.script.common.BusinessException;
import com.urban.script.common.JwtUtil;
import com.urban.script.common.ResultCode;
import com.urban.script.user.dto.LoginReq;
import com.urban.script.user.dto.LoginRes;
import com.urban.script.user.dto.RegisterReq;
import com.urban.script.user.dto.UserProfileRes;
import com.urban.script.user.entity.UserInfo;
import com.urban.script.user.mapper.UserMapper;
import com.urban.script.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 用户服务实现
 *
 * @author urban-script-reservation
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    // ===================== 注册 =====================

    @Override
    public Long register(RegisterReq req) {
        // ① 用户名唯一性校验
        UserInfo existing = userMapper.selectByUsername(req.getUsername());
        if (existing != null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "用户名已存在");
        }

        // ② BCrypt 加密密码（强度 10）
        String encoded = passwordEncoder.encode(req.getPassword());

        // ③ 构建实体并 INSERT
        UserInfo user = new UserInfo();
        user.setUsername(req.getUsername());
        user.setPassword(encoded);
        user.setPhone(req.getPhone());
        // 默认值：玩家 + 正常
        user.setRole(0);
        user.setStatus(1);
        userMapper.insert(user);

        log.info("[register] user={} registered, id={}", req.getUsername(), user.getId());
        return user.getId();
    }

    // ===================== 登录 =====================

    @Override
    public LoginRes login(LoginReq req) {
        // ① 查用户
        UserInfo user = userMapper.selectByUsername(req.getUsername());
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "用户不存在");
        }

        // ② 校验密码（BCrypt matches 自动处理盐 + 强度）
        if (!passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "密码错误");
        }

        // ③ 检查账号状态
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "账号已禁用");
        }

        // ④ 签发 JWT（密钥由 JwtAutoConfiguration 从 Nacos 统一注入 JwtUtil，这里不再单独设 secret）
        String roleStr = roleIntToStr(user.getRole());
        String token = JwtUtil.generateToken(user.getId(), roleStr);

        log.info("[login] user={} login success, role={}", user.getUsername(), roleStr);

        return LoginRes.builder()
                .token(token)
                .userId(user.getId())
                .role(roleStr)
                .build();
    }

    // ===================== 个人信息 =====================

    @Override
    public UserProfileRes getProfile(Long userId) {
        UserInfo user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "用户不存在");
        }

        return UserProfileRes.builder()
                .id(user.getId())
                .username(user.getUsername())
                .phone(maskPhone(user.getPhone()))   // 脱敏
                .avatar(user.getAvatar())
                .role(roleIntToStr(user.getRole()))
                .status(user.getStatus())
                .createTime(user.getCreateTime())
                .build();
    }

    // ===================== 工具方法 =====================

    /**
     * 角色 Integer → "ROLE_xxx" 字符串（JWT claim & Gateway 鉴权统一用这个格式）
     */
    private static String roleIntToStr(Integer role) {
        if (role == null) {
            return "ROLE_PLAYER"; // 默认
        }
        return switch (role) {
            case 0 -> "ROLE_PLAYER";
            case 1 -> "ROLE_DM";
            case 2 -> "ROLE_SHOP_OWNER";
            case 3 -> "ROLE_ADMIN";
            default -> "ROLE_PLAYER";
        };
    }

    /**
     * 手机号脱敏：中间 4 位用 **** 替换
     * 13800138000 → 138****8000
     */
    private static String maskPhone(String phone) {
        if (phone == null || phone.length() != 11) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }
}
