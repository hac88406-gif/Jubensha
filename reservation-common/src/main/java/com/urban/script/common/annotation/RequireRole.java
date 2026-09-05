package com.urban.script.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 角色权限注解
 *
 * <p>标注在 Controller 方法或类上，表示该接口 / 该类下所有接口需要指定角色才能访问。
 * 由 Gateway 或业务服务的 AOP 切面统一解析处理。
 *
 * <p>使用示例：
 * <pre>{@code
 * @RequireRole({"ADMIN", "SHOP_OWNER"})
 * @GetMapping("/shop/manage")
 * public R<?> manageShop() { ... }
 * }</pre>
 *
 * @author urban-script-reservation
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {

    /**
     * 允许访问的角色编码数组，例如 {"USER", "ADMIN", "DM", "SHOP_OWNER"}。
     * <p>数组为空时表示只需要登录，不限制具体角色。
     */
    String[] value() default {};
}
