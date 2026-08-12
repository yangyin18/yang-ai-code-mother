package com.cg.yangaicodemother.ai;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;

/**
 * Vue 项目生成 AI 服务。
 *
 * <p>与 {@link AiCodeGeneratorService}（模型一次性输出 JSON 字符串，后端反序列化+落盘）不同，
 * 本服务走「工具调用（agent）」：system prompt 要求模型扮演 agent，
 * 通过文件写入工具逐个生成项目文件（见 {@code prompt/codegen-vue-project-system-prompt.txt}）。
 * 工具实例与 token 预算由调用方（{@link com.cg.yangaicodemother.core.CodeGenFacade}）
 * 每次生成临时构造并注入，因此本接口不注册为 Spring Bean，由 AiServices 按需构建。
 */
public interface AiVueProjectService {

    /**
     * Vue 项目生成（同步）：模型完整跑完「计划 → 逐个写文件 → 完毕提示」后返回最终文本。
     * 文件由工具直接落到磁盘，返回值为模型最后一条消息。
     *
     * @param userMessage 用户需求描述
     * @return 模型最终回复文本
     */
    @SystemMessage(fromResource = "prompt/codegen-vue-project-system-prompt.txt")
    String generateVueProject(String userMessage);

    /**
     * Vue 项目生成（流式）：通过 {@link TokenStream} 边生成边回调。
     * 计划/完毕提示的文本走 onPartialResponse；每个文件写入回调工具的 {@code onFileWritten}。
     *
     * @param userMessage 用户需求描述
     * @return TokenStream，调用方注册回调后 start()
     */
    @SystemMessage(fromResource = "prompt/codegen-vue-project-system-prompt.txt")
    TokenStream generateVueProjectStream(String userMessage);
}
