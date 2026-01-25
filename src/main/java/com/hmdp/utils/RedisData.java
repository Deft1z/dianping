package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 封装逻辑过期数据类
 */
@Data
public class RedisData {
    //过期时间
    private LocalDateTime expireTime;
    //封装数据
    private Object data;
}
