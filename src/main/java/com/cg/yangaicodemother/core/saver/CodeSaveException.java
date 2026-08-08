package com.cg.yangaicodemother.core.saver;

/**
 * 代码保存失败异常。
 *
 * <p>当待保存的文件列表为空、目录创建失败或文件写盘异常时抛出，
 * 由调用方（如 {@code CodeGenFacade} 的流式 complete 处理）捕获后转成业务错误回调。
 */
public class CodeSaveException extends RuntimeException {

    public CodeSaveException(String message) {
        super(message);
    }

    public CodeSaveException(String message, Throwable cause) {
        super(message, cause);
    }
}
