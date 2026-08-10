package com.cg.yangaicodemother.ai;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * 手动构建 ChatModel Bean。
 *
 * <p>langchain4j 的 spring-boot-starter（含 1.18.1-beta28，即当前最新）仍基于 Spring Boot 3.5 编译，
 * 其自动配置使用的 SpringRestClient 依赖 org.springframework.boot.http.client.ClientHttpRequestFactorySettings，
 * 该类在 Spring Boot 4 中已被移除，导致启动时抛 NoClassDefFoundError。
 * 因此不引入 starter，改用 langchain4j-open-ai 核心包（默认走 JDK HttpClient，无 Spring 依赖）自行装配，
 * 配置项与官方 starter 保持一致（langchain4j.open-ai.chat-model.*）。
 */
@Configuration
public class ChatModelConfig {

    @Value("${langchain4j.open-ai.chat-model.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${langchain4j.open-ai.chat-model.api-key:}")
    private String apiKey;

    @Value("${langchain4j.open-ai.chat-model.model-name:deepseek-chat}")
    private String modelName;

    @Value("${langchain4j.open-ai.chat-model.log-requests:false}")
    private Boolean logRequests;

    @Value("${langchain4j.open-ai.chat-model.log-responses:false}")
    private Boolean logResponses;

    /**
     * 关闭 DeepSeek 推理模型的思考过程。
     *
     * <p>实测代码生成慢的根因：模型 deepseek-v4-flash 是推理模型，对代码生成提示词
     * 先输出 1.4w~4.2w 字符的 reasoning_content 才输出正文，首个正文 token 要等 38~95s；
     * 通过 customParameters 附加 {@code "thinking": {"type": "disabled"}} 后，
     * 首个正文 token 降至 ~1.3s，总耗时从 ~95s 降到 ~10s。该参数对非推理模型无副作用。
     */
    private static final Map<String, Object> DISABLE_THINKING =
            Map.of("thinking", Map.of("type", "disabled"));

    @Bean
    public ChatModel chatModel() {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .logRequests(logRequests)
                .logResponses(logResponses)
                .customParameters(DISABLE_THINKING)
                .build();
    }

    /**
     * 流式 ChatModel：供返回 {@code TokenStream} 的流式方法使用。
     * 配置项与非流式一致，仅换用 OpenAiStreamingChatModel。
     */
    @Bean
    public StreamingChatModel streamingChatModel() {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .logRequests(logRequests)
                .logResponses(logResponses)
                .customParameters(DISABLE_THINKING)
                .build();
    }
}
