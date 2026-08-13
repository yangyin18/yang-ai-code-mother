package com.cg.yangaicodemother.core;

/**
 * 代码生成流式回调。
 *
 * <p>对应 langchain4j {@code TokenStream} 的三个阶段：
 * 生成过程中的增量文本、完整结束后的结果、以及异常。
 * 由调用方（如 SSE Controller）实现，前端可据此做「打字机」效果。
 */
public interface CodeGenStreamCallback {

    /**
     * 收到一段增量文本时回调（可能多次）
     *
     * @param partialText 截至目前累积的文本增量
     */
    void onPartial(String partialText);

    /**
     * 流式结束、完整代码已解析并落盘后回调
     *
     * @param result 最终生成结果（含代码内容与保存目录）
     */
    void onComplete(CodeGenResult result);

    /**
     * 任一步骤出错时回调
     *
     * @param error 异常
     */
    void onError(Throwable error);

    /**
     * 生成是否已被取消（如客户端中途断开连接后，调用方主动中断生成）。
     * 门面在解析 / 落盘 / 加固前会检查；返回 true 时跳过一切副作用（不再保存、不再补桩、不再写 Vue 文件）。
     * 默认返回 false，普通调用方无需改动。
     *
     * @return true 表示生成已取消
     */
    default boolean isCancelled() {
        return false;
    }

    /**
     * Vue 项目模式下，每个项目文件写入成功后回调（可多次）。
     * HTML / 多文件模式不触发，默认空实现，无需各调用方改动。
     *
     * @param path 项目内相对路径（如 src/App.vue）
     */
    default void onFileWritten(String path) {
        // no-op
    }
}
