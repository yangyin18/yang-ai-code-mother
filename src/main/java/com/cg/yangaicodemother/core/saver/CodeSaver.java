package com.cg.yangaicodemother.core.saver;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.cg.yangaicodemother.ai.model.HtmlCodeResult;
import com.cg.yangaicodemother.ai.model.MultiFileCodeResult;
import com.cg.yangaicodemother.core.parser.CodeFile;
import com.cg.yangaicodemother.model.enums.CodeGenTypeEnum;

import java.nio.charset.StandardCharsets;
import java.util.List;

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
        String dirPath = buildDir(bizType, appId);
        for (CodeFile file : files) {
            writeToFile(dirPath, file.name(), file.content());
        }
        return new CodeSaveResult(dirPath,
                files.stream().map(CodeFile::name).toList());
    }

    /**
     * 构建保存目录路径：{@code {root}/{bizType}_{appId}}。
     *
     * @param bizType 业务类型
     * @param appId   应用 id
     * @return 保存目录的完整路径
     */
    private static String buildDir(String bizType, Long appId) {
        String dirName = StrUtil.format("{}_{}", bizType, appId);
        String dirPath = FILE_SAVE_ROOT_DIR + "/" + dirName;
        FileUtil.mkdir(dirPath);
        if (!FileUtil.exist(dirPath) || !FileUtil.isDirectory(dirPath)) {
            throw new CodeSaveException("创建保存目录失败：" + dirPath);
        }
        return dirPath;
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
