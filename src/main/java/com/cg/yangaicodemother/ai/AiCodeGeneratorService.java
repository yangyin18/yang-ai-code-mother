package com.cg.yangaicodemother.ai;

import com.cg.yangaicodemother.ai.model.HtmlCodeResult;
import com.cg.yangaicodemother.ai.model.MultiFileCodeResult;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;

public interface AiCodeGeneratorService {

    /**
     * 单文件 HTML 生成（非流式）
     *
     * @param userMessage 用户需求描述
     * @return HTML 代码结果
     */
    @SystemMessage(fromResource = "prompt/codegen-html-prompt.txt")
    HtmlCodeResult generateCode(String userMessage);

    /**
     * 单文件 HTML 生成（流式）：通过 {@link TokenStream} 边生成边回调
     *
     * @param userMessage 用户需求描述
     * @return TokenStream，调用方注册 onPartialResponse / onCompleteResponse / onError 后 start()
     */
    @SystemMessage(fromResource = "prompt/codegen-html-prompt.txt")
    TokenStream generateCodeStream(String userMessage);

    /**
     * 多文件（html、css、js）生成（非流式）
     *
     * @param userMessage 用户需求描述
     * @return 多文件代码返回实体
     */
    @SystemMessage(fromResource = "prompt/codegen-multi-prompt.txt")
    MultiFileCodeResult generateMultiCode(String userMessage);

    /**
     * 多文件（html、css、js）生成（流式）：通过 {@link TokenStream} 边生成边回调
     *
     * @param userMessage 用户需求描述
     * @return TokenStream，调用方注册 onPartialResponse / onCompleteResponse / onError 后 start()
     */
    @SystemMessage(fromResource = "prompt/codegen-multi-prompt.txt")
    TokenStream generateMultiCodeStream(String userMessage);
}
