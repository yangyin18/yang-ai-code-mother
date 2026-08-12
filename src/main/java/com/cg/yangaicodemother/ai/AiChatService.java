package com.cg.yangaicodemother.ai;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 应用对话服务：给「应用对话页面」提供流式 AI 回复。
 *
 * <p>对话记忆走 langchain4j ChatMemory：以 {@code appId} 为 {@link MemoryId}，
 * 历史由 RedisChatMemoryStore（Redis 短缓存，未命中自动从 MySQL 重建）提供，
 * 这里不再手动拼「最近对话」进 userMessage。
 *
 * <p>与代码生成（{@link AiCodeGeneratorService}）不同，这里不做代码解析与落盘，
 * 只生成一段自然的对话回复。应用说明（initPrompt）通过 {@code appDescription}
 * 注入 system 模板，用户当前消息经 {@link UserMessage} 单独传入。
 */
public interface AiChatService {

    /**
     * 流式对话回复：通过 {@link TokenStream} 边生成边回调。
     *
     * @param appId          应用 id（记忆槽位，history 存 Redis 短缓存）
     * @param appDescription 应用说明（注入 system 模板，保持应用上下文）
     * @param userMessage    用户当前消息（不加历史，历史由记忆提供）
     * @return TokenStream，调用方注册 onPartialResponse / onCompleteResponse / onError 后 start()
     */
    @SystemMessage(value = """
            你是一个 AI 零代码应用平台的应用助手，正在帮助用户打磨他们用一句话生成的网页应用。

            请遵循以下规则：
            1. 用简体中文回复，语气友好、简洁，直接回答用户问题。
            2. 回复正文只输出对话内容，不要输出 JSON、代码块或 Markdown 标记。
            3. 如果用户想调整应用的外观或功能，用一两句话说明可以如何调整，并给出可执行的建议。
            4. 不要编造用户没有提到的功能或链接，不知道的内容如实说明。

            当前正在打磨的应用说明：{{appDescription}}""")
    TokenStream chatStream(@MemoryId Long appId,
                           @V("appDescription") String appDescription,
                           @UserMessage String userMessage);
}
