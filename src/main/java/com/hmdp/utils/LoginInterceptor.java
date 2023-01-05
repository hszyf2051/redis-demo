package com.hmdp.utils;

import com.baomidou.mybatisplus.core.toolkit.ObjectUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * @author yif
 */
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 判断是否需要拦截（ThreadLocal中是否有用户）
        if (ObjectUtils.isEmpty(UserHolder.getUser())) {
            response.setStatus(401);
            return false;
        }
        return true;
    }
}
