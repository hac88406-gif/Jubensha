package com.urban.script.shop.aspect;

import com.urban.script.common.R;
import com.urban.script.common.ResultCode;
import com.urban.script.common.annotation.RequireRole;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

/**
 * 角色鉴权切面（shop-service 自己的拷贝）
 *
 * <p>拦截所有标注了 {@link RequireRole} 的 Controller 方法 / 类。
 * 逻辑同 user-service 的 RoleAspect，独立部署的微服务各自持有一份。
 *
 * @author urban-script-reservation
 */
@Slf4j
@Aspect
@Component
public class RoleAspect {

    @Around("@annotation(requireRole) || @within(requireRole)")
    public Object around(ProceedingJoinPoint pjp, RequireRole requireRole) throws Throwable {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return pjp.proceed();
        }
        HttpServletRequest request = attrs.getRequest();

        String userId = request.getHeader("X-User-Id");
        String role = request.getHeader("X-User-Role");

        if (userId == null || userId.isBlank() || role == null || role.isBlank()) {
            log.warn("[shop.RoleAspect] unauthenticated: uri={}", request.getRequestURI());
            return R.fail(ResultCode.UNAUTHORIZED.getCode(), "未登录或 Token 无效");
        }

        RequireRole effective = resolveRequireRole(pjp, requireRole);
        String[] allowedRoles = effective.value();

        if (allowedRoles.length == 0) {
            return pjp.proceed();
        }

        for (String allowed : allowedRoles) {
            if (allowed.equalsIgnoreCase(role)) {
                return pjp.proceed();
            }
        }

        log.warn("[shop.RoleAspect] forbidden: userId={}, role={}, uri={}, allowed={}",
                userId, role, request.getRequestURI(), String.join(",", allowedRoles));
        return R.fail(ResultCode.FORBIDDEN.getCode(),
                "无权限访问，当前角色: " + role);
    }

    private RequireRole resolveRequireRole(ProceedingJoinPoint pjp, RequireRole classLevel) {
        if (pjp.getSignature() instanceof MethodSignature ms) {
            Method method = ms.getMethod();
            RequireRole methodLevel = method.getAnnotation(RequireRole.class);
            if (methodLevel != null) {
                return methodLevel;
            }
        }
        return classLevel;
    }
}
