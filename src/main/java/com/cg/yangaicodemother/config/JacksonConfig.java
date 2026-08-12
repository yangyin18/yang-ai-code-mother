package com.cg.yangaicodemother.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * Jackson 全局配置。
 *
 * <p>把 {@code Long / long} 序列化为字符串：本项目的数据库主键是 MyBatis-Flex 雪花 ID
 * （64 位，约 4.4e17），超出 JS 的 Number 安全整数范围（2^53≈9e15），
 * 前端 {@code JSON.parse} 会丢精度，导致把错误的 id 再发回后端（如生成时报「应用不存在」）。
 * 全局转字符串后，前端把 id 当字符串透传即可，不会丢精度。
 *
 * <p>注意：分页信息（pageNum / pageSize / total）也会变成字符串，
 * 本系统前端目前不使用这些字段，无影响。
 */
@Configuration
public class JacksonConfig {

    /** 把 Long/long 序列化成字符串的模块，Spring Boot 自动注册进 ObjectMapper */
    @Bean
    public JacksonModule longToStringModule() {
        SimpleModule module = new SimpleModule("LongToString");
        module.addSerializer(Long.class, ToStringSerializer.instance);
        module.addSerializer(Long.TYPE, ToStringSerializer.instance);
        return module;

    }
}
