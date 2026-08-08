package com.cg.yangaicodemother.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 登录鉴权注解。
 *
 * <p>标注在 Controller 接口方法上，由 {@code aop.AuthInterceptor} 切面统一校验：
 * <ul>
 *   <li>不写任何属性：仅要求已登录（普通用户即可）；</li>
 *   <li>role = "admin"：仅管理员可访问，普通用户抛 NO_AUTH_ERROR；</li>
 *   <li>mustLogin = false：不校验登录态（一般与 role 搭配很少用到）。</li>
 * </ul>
 *
 * <p>角色取值见 {@link UserRoleEnum}：USER = "user"，ADMIN = "admin"。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuthCheck {

    /**
     * 要求的角色（UserRoleEnum 的 value），默认空串：只要登录即可。
     */
    String role() default "";

    /**
     * 是否必须登录，默认 true。
     */
    boolean mustLogin() default true;

}
