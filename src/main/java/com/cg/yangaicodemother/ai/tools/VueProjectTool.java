package com.cg.yangaicodemother.ai.tools;

import cn.hutool.core.io.FileUtil;
import dev.langchain4j.agent.tool.Tool;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Vue 项目生成的文件写入工具（langchain4j {@link Tool}，每次生成一个实例）。
 *
 * <p>模型按 {@code prompt/codegen-vue-project-system-prompt.txt} 的要求，
 * 通过 {@code writeFile} 逐个把项目文件写入磁盘（支持 {@code src/App.vue} 等嵌套路径，
 * FileUtil 自动创建父目录），生成完调用 {@code finishProject} 提交项目描述。
 *
 * <p>安全与限制：
 * <ul>
 *   <li><b>路径安全</b>：只接受项目内的相对路径，拒绝 {@code ..}、绝对路径、反斜杠、盘符路径，
 *       防止模型把文件写到项目目录之外；</li>
 *   <li><b>token/文件数上限</b>：交给 {@link VueProjectTokenBudget} 累计校验，超限返回拒绝原因，
 *       引导模型停止生成；</li>
 *   <li><b>不净化代码</b>：Vue 提示词明确允许 {@code picsum.photos} 等外部占位图，
 *       与 {@link com.cg.yangaicodemother.core.CodeSanitizer}（移除一切外部链接）冲突，
 *       因此 Vue 模式跳过净化，原样落盘。</li>
 * </ul>
 */
public class VueProjectTool {

    /** 项目保存目录（绝对路径），生成前已清空 */
    private final String projectDir;

    /** 本次生成的 token 预算 */
    private final VueProjectTokenBudget budget;

    /** 每个文件写入成功后的回调（SSE 推送 file 事件） */
    private final Consumer<String> onFileWritten;

    /** 已写入的相对路径列表（用于 onComplete 组装结果） */
    private final List<String> writtenPaths = new CopyOnWriteArrayList<>();

    /** 模型通过 finishProject 提交的项目描述 */
    private volatile String summary;

    public VueProjectTool(String projectDir, VueProjectTokenBudget budget, Consumer<String> onFileWritten) {
        this.projectDir = projectDir;
        this.budget = budget;
        this.onFileWritten = onFileWritten;
    }

    /**
     * 写入一个项目文件。
     *
     * @param path    项目内相对路径，如 {@code src/App.vue}、{@code package.json}
     * @param content 文件内容
     * @return 成功/失败提示（失败信息会回传给模型，供其停止或修正）
     */
    @Tool("把 Vue 项目的一个文件写入磁盘。path 必须是项目内的相对路径（如 src/App.vue、package.json、vite.config.js），禁止使用 .. 或绝对路径。")
    public String writeFile(String path, String content) {
        if (path == null || content == null) {
            return "参数错误：path 与 content 不能为空。";
        }
        String safePath = validateAndNormalizePath(path);
        if (safePath == null) {
            return "非法路径：" + path + "。路径必须是项目内的相对路径，禁止使用 ..、绝对路径、反斜杠或盘符。请修正后重试。";
        }
        VueProjectTokenBudget.Result result = budget.tryWrite(content.length());
        if (!result.allowed()) {
            return result.message();
        }
        FileUtil.writeString(content, projectDir + "/" + safePath, StandardCharsets.UTF_8);
        writtenPaths.add(safePath);
        if (onFileWritten != null) {
            onFileWritten.accept(safePath);
        }
        return "已写入 " + safePath + "（已生成 " + budget.fileCount() + "/" + budget.maxFiles() + " 个文件）。";
    }

    /**
     * 全部文件生成完毕后调用，提交项目描述。
     *
     * @param summary 项目功能描述
     * @return 提示
     */
    @Tool("所有项目文件都生成完毕后调用一次，summary 为该项目的功能描述（一句话概括即可）。")
    public String finishProject(String summary) {
        this.summary = summary;
        return "已记录项目描述。请直接输出生成完毕提示。";
    }

    /**
     * 路径安全校验：只允许项目内相对路径。
     * 拒绝：绝对路径（/ 或盘符开头）、反斜杠（统一转 /）、任何包含 {@code ..} 的路径。
     */
    private String validateAndNormalizePath(String path) {
        String normalized = path.trim().replace('\\', '/');
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.startsWith("/")) {
            return null;
        }
        // Windows 盘符路径（C:/...）
        if (normalized.length() >= 2 && Character.isLetter(normalized.charAt(0)) && normalized.charAt(1) == ':') {
            return null;
        }
        if (normalized.contains("..")) {
            return null;
        }
        return normalized;
    }

    public List<String> writtenPaths() {
        return writtenPaths;
    }

    public String summary() {
        return summary;
    }

    public int usedTokens() {
        return budget.usedTokens();
    }
}
