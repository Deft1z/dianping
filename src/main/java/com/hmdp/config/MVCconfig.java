package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.ReflushTokenInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurationSupport;

@Configuration
public class MVCconfig extends WebMvcConfigurationSupport {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    //添加拦截器
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        //登录拦截器 放行这些路径（不需要已登录的状态）
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/user/login", //登录
                        "/user/code", //发送验证码
                        "/blog/hot", //热点
                        "/shop/**", //店铺
                        "/shop-type/**" , //店铺类型
                        "/upload/**", //下载
                        "/voucher/**" //优惠券
                ).order(1); //顺序为1  越小 优先级越高

        //token刷新拦截器
        registry.addInterceptor(new ReflushTokenInterceptor(stringRedisTemplate))
                .addPathPatterns("/**") //默认拦截所有请求 对于所有请求都要拦截去刷新token
                .order(0); //顺序为0 刷新拦截器需要先执行

    }
}
