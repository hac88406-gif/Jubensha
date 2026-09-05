package com.urban.script.user.aspect;

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
 * 角色鉴权切面
 *
 * <p>拦截所有标注了 {@link RequireRole} 的 Controller 方法 / 类：
 * <ol>
 *   <li>从请求头取 {@code X-User-Id} 和 {@code X-User-Role}
 *       （Gateway 从 JWT 解析后注入；直连时 Postman 手动带）</li>
 *   <li>Header 缺失 → 未登录 → 返回 R.fail(401)</li>
 *   <li>Header 存在但 role 不在允许列表 → 返回 R.fail(403)</li>
 *   <li>全部 OK → 放行执行 pjp.proceed()</li>
 * </ol>
 *
 * <p>为什么 Controller 参数的 @RequestHeader 必须设 required=false？
 * 因为 Spring MVC 参数绑定在 AOP 之前执行 —— 如果 required=true 且 Header 缺失，
 * Spring 会在 {@code MethodArgumentResolutionException} 阶段就失败，
 * {@code RoleAspect} 根本没机会运行。所以 Controller 方法签名要"放开来"，
 * 真正的登录校验交给这个切面做。
 *
 * @author urban-script-reservation
 */
@Slf4j
@Aspect
@Component
public class RoleAspect {

    /**
     * 切点：方法上有 @RequireRole，或类上有 @RequireRole
     */
    @Around("@annotation(requireRole) || @within(requireRole)")
    public Object around(ProceedingJoinPoint pjp, RequireRole requireRole) throws Throwable {
        // 1. 拿到当前 HttpServletRequest（通过 Spring 的 RequestContextHolder 线程变量）
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            // 非 Web 上下文（比如单元测试），直接放行
            return pjp.proceed();
        }
        HttpServletRequest request = attrs.getRequest();

        // 2. 提取 Header（Gateway 注入 / Postman 手动带）
        String userId = request.getHeader("X-User-Id");
        String role = request.getHeader("X-User-Role");

        // 3. 未登录 —— Header 缺失（Controller 的 @RequestHeader 必须是 required=false）
        if (userId == null || userId.isBlank() || role == null || role.isBlank()) {
            log.warn("[RoleAspect] unauthenticated access: uri={}, remote={}",
                    request.getRequestURI(), request.getRemoteAddr());
            return R.fail(ResultCode.UNAUTHORIZED.getCode(), "未登录或 Token 无效");
        }

        // 4. 从注解拿允许的角色数组
        //    注解可能在类上也可能在方法上，优先用方法级的（后者覆盖前者）
        RequireRole effective = resolveRequireRole(pjp, requireRole);
        String[] allowedRoles = effective.value();

        // 5. 空数组 = 只要求登录，不限制具体角色
        if (allowedRoles.length == 0) {
            return pjp.proceed();
        }

        // 6. 遍历比对角色（忽略大小写）
        for (String allowed : allowedRoles) {
            if (allowed.equalsIgnoreCase(role)) {
                // ✅ 放行
                return pjp.proceed();
            }
        }

        // 7. 无权限
        log.warn("[RoleAspect] forbidden: userId={}, role={}, uri={}, allowed={}",
                userId, role, request.getRequestURI(), String.join(",", allowedRoles));
        return R.fail(ResultCode.FORBIDDEN.getCode(),
                "无权限访问，当前角色: " + role);
    }

    /**
     * 方法级 @RequireRole 覆盖类级 @RequireRole
     * 如果方法上有注解就用方法的；否则用切面绑定进来的那个（就是类上的）
     */
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
