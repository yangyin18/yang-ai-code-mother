package com.cg.yangaicodemother.core.saver;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.cg.yangaicodemother.ai.model.HtmlCodeResult;
import com.cg.yangaicodemother.ai.model.MultiFileCodeResult;
import com.cg.yangaicodemother.core.CodeSanitizer;
import com.cg.yangaicodemother.core.parser.CodeFile;
import com.cg.yangaicodemother.model.enums.CodeGenTypeEnum;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 代码保存器：把解析好的代码文件落到磁盘上。
 *
 * <p>保存目录按「应用 id」命名（{bizType}_{appId}，如 html_1001 / multi_file_1001），
 * 把一次生成的代码归到所属应用下；同一应用再次生成会落到同一个目录（覆盖式更新）。
 *
 * <p>使用方式（静态方法，无状态）：
 * <pre>{@code
 * CodeSaveResult result = CodeSaver.saveFiles(parsed.files(), CodeGenTypeEnum.HTML.getValue(), appId);
 * result.dir();            // 保存目录
 * result.saveDir();        // 绝对路径字符串
 * }</pre>
 */
public final class CodeSaver {

    private CodeSaver() {
    }

    /** 文件保存的根目录：{user.dir}/tmp/code_output，每次生成在其下建 {bizType}_{appId} 子目录 */
    private static final String FILE_SAVE_ROOT_DIR = System.getProperty("user.dir") + "/tmp/code_output";

    /** 注入 codegen 上下文的当前代码总字符上限：防止大文件（尤其 Vue 工程）撑爆 prompt，超限截断 */
    private static final int MAX_CURRENT_CODE_CHARS = 20000;

    /** 可注入 codegen 上下文的文本扩展名（二进制/图片等一律跳过，避免乱码与浪费 token） */
    private static final Set<String> CODE_CONTEXT_EXTENSIONS = Set.of(
            "html", "css", "js", "mjs", "cjs", "vue", "json", "txt", "md");

    /**
     * 保存单文件 HTML 代码。
     *
     * @param htmlCodeResult 单文件 HTML 生成结果实体
     * @param appId          应用 id，用于命名保存目录
     * @return 保存结果（含目录与文件名）
     */
    public static CodeSaveResult saveHtml(HtmlCodeResult htmlCodeResult, Long appId) {
        return saveFiles(
                List.of(new CodeFile("index.html", htmlCodeResult.getHtmlCode())),
                CodeGenTypeEnum.HTML.getValue(), appId);
    }

    /**
     * 保存多文件（index.html + style.css + script.js）代码。
     *
     * @param result 多文件代码生成结果实体
     * @param appId  应用 id，用于命名保存目录
     * @return 保存结果（含目录与文件名）
     */
    public static CodeSaveResult saveMultiFile(MultiFileCodeResult result, Long appId) {
        return saveFiles(
                List.of(
                        new CodeFile("index.html", result.getHtmlCode()),
                        new CodeFile("style.css", result.getCssCode()),
                        new CodeFile("script.js", result.getJsCode())),
                CodeGenTypeEnum.MULTI_FILE.getValue(), appId);
    }

    /**
     * 通用保存：把一组代码文件写到 {@code {bizType}_{appId}} 目录。
     *
     * @param files   代码文件列表（{@link CodeFile}）
     * @param bizType 业务类型（html / multi_file），用于目录命名
     * @param appId   应用 id，用于目录命名（必填）
     * @return 保存结果（含目录与文件名）
     * @throws CodeSaveException 应用 id 为空 / 文件列表为空 / 目录创建失败 / 写盘异常
     */
    public static CodeSaveResult saveFiles(List<CodeFile> files, String bizType, Long appId) {
        if (files == null || files.isEmpty()) {
            throw new CodeSaveException("没有可保存的代码文件");
        }
        if (appId == null) {
            throw new CodeSaveException("应用 id 不能为空");
        }
        String dirPath = resolveDir(bizType, appId);
        for (CodeFile file : files) {
            // 落盘前兜底净化：移除生成代码里的一切外部链接与跳转（已净化过则幂等）
            writeToFile(dirPath, file.name(), CodeSanitizer.sanitize(file.content(), file.name()));
        }
        return new CodeSaveResult(dirPath,
                files.stream().map(CodeFile::name).toList());
    }

    /**
     * 构建保存目录路径：{@code {root}/{bizType}_{appId}}，不存在则创建。
     *
     * <p>Vue 项目生成走工具调用、逐个写文件，也需要复用这个目录规则
     * （{@code vue_{appId}}），故提升为 public 供门面/工具使用。
     *
     * @param bizType 业务类型（html / multi_file / vue）
     * @param appId   应用 id
     * @return 保存目录的完整路径
     */
    public static String resolveDir(String bizType, Long appId) {
        String dirName = StrUtil.format("{}_{}", bizType, appId);
        String dirPath = FILE_SAVE_ROOT_DIR + "/" + dirName;
        FileUtil.mkdir(dirPath);
        if (!FileUtil.exist(dirPath) || !FileUtil.isDirectory(dirPath)) {
            throw new CodeSaveException("创建保存目录失败：" + dirPath);
        }
        return dirPath;
    }

    /**
     * 只读定位 {@code {bizType}_{appId}} 目录：存在返回路径，不存在返回 null（不创建目录）。
     *
     * <p>供服务端内部读取「当前已生成代码」作为上下文（如对话即改代码时把现有代码
     * 作为小幅度修改基线注入 AI），与 {@link #resolveDir} 的区别是绝不落盘副作用。
     *
     * @param bizType 业务类型（html / multi_file / vue）
     * @param appId   应用 id
     * @return 目录路径，不存在返回 null
     */
    public static String resolveExistingDir(String bizType, Long appId) {
        if (appId == null) {
            return null;
        }
        String dirPath = FILE_SAVE_ROOT_DIR + "/" + StrUtil.format("{}_{}", bizType, appId);
        return FileUtil.isDirectory(dirPath) ? dirPath : null;
    }

    /**
     * 读取当前已生成代码并拼成注入 codegen 上下文的文本（「小幅度修改」基线）。
     *
     * <p>只读定位（不创建目录），按文件名排序稳定，跳过 node_modules / dist / .git 目录
     * 与非文本扩展名，总量受 {@link #MAX_CURRENT_CODE_CHARS} 约束——超限截断并在末尾提示，
     * 避免超大工程（尤其 Vue 的 node_modules 误入）撑爆 prompt。服务端内部使用，
     * 拼出的文本进 AI 上下文、绝不返回客户端。
     *
     * @param bizType 业务类型（html / multi_file / vue）
     * @param appId   应用 id
     * @return 拼好的代码上下文段；目录不存在 / 无文本文件时返回空串
     */
    /**
     * 递归收集文本代码文件（相对顺序稳定）：跳过 node_modules / dist / .git 目录，
     * 只保留 {@link #CODE_CONTEXT_EXTENSIONS} 里的文本扩展名。每层按文件名排序，
     * 深度优先遍历，输出顺序确定。
     */
    private static void collectTextFiles(File dir, List<File> out) {
        File[] subs = dir.listFiles();
        if (subs == null) {
            return;
        }
        java.util.Arrays.sort(subs, Comparator.comparing(File::getName));
        for (File f : subs) {
            if (f.isDirectory()) {
                String n = f.getName();
                if ("node_modules".equals(n) || "dist".equals(n) || ".git".equals(n)) {
                    continue;
                }
                collectTextFiles(f, out);
            } else {
                String ext = FileUtil.extName(f).toLowerCase();
                if (CODE_CONTEXT_EXTENSIONS.contains(ext)) {
                    out.add(f);
                }
            }
        }
    }

    public static String buildCurrentCodeContext(String bizType, Long appId) {
        String dirPath = resolveExistingDir(bizType, appId);
        if (dirPath == null) {
            return "";
        }
        File dir = FileUtil.file(dirPath);
        // 目录级剪枝：排除依赖/构建产物，避免把 node_modules 里成千上万的文件扫进 prompt。
        // 手动递归（每层按名字排序）而非 hutool loopFiles 的 filter，剪枝行为完全确定。
        List<File> files = new ArrayList<>();
        collectTextFiles(dir, files);
        if (files.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n当前应用代码（请以此代码为基线做【小幅度修改】：只改动用户要求的部分，"
                + "其它内容、样式、结构原样保留，禁止整页重写）：\n");
        int budget = MAX_CURRENT_CODE_CHARS;
        for (File f : files) {
            String rel = dir.toPath().relativize(f.toPath()).toString().replace('\\', '/');
            String content;
            try {
                content = FileUtil.readUtf8String(f);
            } catch (Exception e) {
                // 个别文件读不了（编码/权限）跳过，不阻塞上下文
                continue;
            }
            if (content.length() > budget) {
                sb.append("// ===== ").append(rel).append(" =====\n")
                        .append(content, 0, budget)
                        .append("\n// …(该文件过长已截断，请保持未展示部分不变，只做局部修改)\n");
                budget = 0;
                break;
            }
            sb.append("// ===== ").append(rel).append(" =====\n").append(content).append('\n');
            budget -= content.length();
            if (budget <= 0) {
                break;
            }
        }
        if (budget <= 0) {
            sb.append("// …(代码总量过长，仅提供前段作为基线，其余部分请保持原样)\n");
        }
        return sb.toString();
    }

    /**
     * 保存单个文件（UTF-8）。
     *
     * @param dirPath  文件夹路径
     * @param filename 文件名称
     * @param content  文件文本内容
     */
    private static void writeToFile(String dirPath, String filename, String content) {
        String filePath = dirPath + "/" + filename;
        FileUtil.writeString(content, filePath, StandardCharsets.UTF_8);
    }
}
