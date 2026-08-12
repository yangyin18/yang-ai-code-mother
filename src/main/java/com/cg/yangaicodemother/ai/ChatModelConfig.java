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
     * Vue 项目生成的单轮输出 token 上限（对应 codegen-vue-project 提示词「不设硬上限，以完整项目为准」）。
     * 文件内容作为 writeFile 工具参数输出，也计入单轮 max_tokens，因此该值同时封顶单文件大小；
     * 跨轮的累计用量由 {@link com.cg.yangaicodemother.ai.tools.VueProjectTokenBudget} 兜底。
     * 默认设为足够大的值，保证模型能写完完整项目而不是被预算截断。
     */
    @Value("${code.vue.max-tokens:200000}")
    private Integer vueMaxTokens;

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

    /**
     * Vue 项目生成专用的流式 ChatModel。
     *
     * <p>与 {@link #streamingChatModel()} 唯一区别是设置了 {@code maxTokens}（{@code code.vue.max-tokens}）：
     * Vue 生成走工具调用，模型会把整个文件内容作为 writeFile 工具参数输出，
     * 单轮 max_tokens 就是该轮输出的硬上限，防止单个超大文件/超长文本刷爆用量。
     * 其余配置（base-url / api-key / 禁用 thinking）与通用模型一致。
     */
    @Bean
    public StreamingChatModel vueStreamingChatModel() {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .maxTokens(vueMaxTokens)
                .logRequests(logRequests)
                .logResponses(logResponses)
                .customParameters(DISABLE_THINKING)
                .build();
    }
}
