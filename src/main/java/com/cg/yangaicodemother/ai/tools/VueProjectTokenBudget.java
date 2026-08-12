package com.cg.yangaicodemother.ai.tools;

/**
 * Vue 项目生成的 token 用量预算（每次生成一个实例）。
 *
 * <p>在模型层 max_tokens 硬上限之外，工具层再对「文件内容」做累计预算：
 * 每个文件的 token 量按字符数上界估算（中文≈1 字符/token，ASCII 码≈3~4 字符/token，
 * 直接用字符数做上界，宁可少放行），累计超过 {@code maxTokens} 即拒绝继续写入，
 * 并把拒绝原因作为工具返回值告诉模型，让模型停止生成。
 * 同时按 {@code maxFiles} 限制文件总数（对应提示词里的「文件总数＜30」）。
 *
 * <p>langchain4j 工具在单条流上串行执行，用 synchronized 保护计数即可。
 */
public class VueProjectTokenBudget {

    /** 工具返回的拒绝结果 */
    public record Result(boolean allowed, String message) {

        public static Result ofAllowed() {
            return new Result(true, null);
        }

        public static Result ofRejected(String message) {
            return new Result(false, message);
        }
    }

    private final int maxTokens;

    private final int maxFiles;

    /** 已累计的估算 token 数 */
    private int usedTokens;

    /** 已写入的文件数 */
    private int fileCount;

    public VueProjectTokenBudget(int maxTokens, int maxFiles) {
        this.maxTokens = maxTokens;
        this.maxFiles = maxFiles;
    }

    /**
     * 尝试写入一个文件：校验文件数上限与 token 累计预算。
     *
     * @param contentLength 文件内容长度（字符数，作为 token 上界估算）
     * @return allowed / rejected
     */
    public synchronized Result tryWrite(int contentLength) {
        if (fileCount >= maxFiles) {
            return Result.ofRejected("文件数量已达上限（" + maxFiles + " 个）。请停止生成项目文件，直接输出生成完毕提示。");
        }
        if (usedTokens + contentLength > maxTokens) {
            return Result.ofRejected("token 用量已达上限（" + maxTokens + "）。请停止生成项目文件，直接输出生成完毕提示。");
        }
        usedTokens += contentLength;
        fileCount++;
        return Result.ofAllowed();
    }

    public int maxTokens() {
        return maxTokens;
    }

    public int maxFiles() {
        return maxFiles;
    }

    public synchronized int usedTokens() {
        return usedTokens;
    }

    public synchronized int fileCount() {
        return fileCount;
    }
}
