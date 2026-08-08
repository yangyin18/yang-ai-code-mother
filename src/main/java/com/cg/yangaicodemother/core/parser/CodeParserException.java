package com.cg.yangaicodemother.core.parser;

/**
 * 代码解析失败异常。
 *
 * <p>当模型输出里既找不到合法 JSON、也找不到任何代码块 / HTML 内容时抛出，
 * 由调用方（如 {@code CodeGenFacade} 的流式 complete 处理）捕获后转成业务错误回调。
 */
public class CodeParserException extends RuntimeException {

    public CodeParserException(String message) {
        super(message);
    }

    public CodeParserException(String message, Throwable cause) {
        super(message, cause);
    }
}
