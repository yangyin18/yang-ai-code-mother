package com.cg.yangaicodemother.aop;

import cn.hutool.core.util.StrUtil;
import com.cg.yangaicodemother.annotation.AuthCheck;
import com.cg.yangaicodemother.exception.BusinessException;
import com.cg.yangaicodemother.exception.ErrorCode;
import com.cg.yangaicodemother.model.vo.LoginUserVO;
import com.cg.yangaicodemother.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 登录鉴权切面。
 *
 * <p>拦截所有标注了 {@link AuthCheck} 的接口方法：
 * <ul>
 *   <li>要求登录时（mustLogin=true 或指定了 role）：通过 {@link UserService#getLoginUser} 校验登录态，
 *       未登录抛 NOT_LOGIN_ERROR；</li>
 *   <li>指定了 role 时：校验当前用户角色是否匹配，不匹配抛 NO_AUTH_ERROR。</li>
 * </ul>
 */
@Aspect
@Component
public class AuthInterceptor {

    @Resource
    private UserService userService;

    @Around("@annotation(authCheck)")
    public Object doInterceptor(ProceedingJoinPoint joinPoint, AuthCheck authCheck) throws Throwable {
        boolean needLogin = authCheck.mustLogin() || StrUtil.isNotBlank(authCheck.role());
        if (needLogin) {
            // 未登录时 getLoginUser 会抛 NOT_LOGIN_ERROR
            LoginUserVO loginUser = userService.getLoginUser(getRequest());
            // 角色校验
            String requiredRole = authCheck.role();
            if (StrUtil.isNotBlank(requiredRole) && !requiredRole.equals(loginUser.getUserRole())) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限访问，需要角色：" + requiredRole);
            }
        }
        return joinPoint.proceed();
    }

    private HttpServletRequest getRequest() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
        return attributes.getRequest();
    }

}
