package com.cg.yangaicodemother.core.saver;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.cg.yangaicodemother.ai.model.HtmlCodeResult;
import com.cg.yangaicodemother.ai.model.MultiFileCodeResult;
import com.cg.yangaicodemother.core.parser.CodeFile;
import com.cg.yangaicodemother.model.enums.CodeGenTypeEnum;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 代码保存器：把解析好的代码文件落到磁盘上的唯一目录里。
 *
 * <p>为什么要单独成类：保存逻辑与「生成 / 解析」解耦，且保存要做三件固定的事——
 * ① 为每次生成建一个唯一目录（雪花 ID 命名，互不覆盖）；
 * ② 按目录隔离 HTML / 多文件等不同业务类型；
 * ③ 统一 UTF-8 写盘、统一返回 {@link CodeSaveResult} 供调用方拼访问路径。
 *
 * <p>使用方式（静态方法，无状态）：
 * <pre>{@code
 * CodeSaveResult result = CodeSaver.saveFiles(parsed.files(), CodeGenTypeEnum.HTML.getValue());
 * result.dir();            // 保存目录
 * result.saveDir();        // 绝对路径字符串
 * }</pre>
 *
 * <p>兼容层：{@code com.cg.yangaicodemother.core.CodeFileSaver} 已改为本类的薄委托，
 * 历史调用方拿到的是目录 {@code File}。
 */
public final class CodeSaver {

    private CodeSaver() {
    }

    /** 文件保存的根目录：{user.dir}/tmp/code_output，每次生成在其下再建一个唯一子目录 */
    private static final String FILE_SAVE_ROOT_DIR = System.getProperty("user.dir") + "/tmp/code_output";

    /**
     * 保存单文件 HTML 代码。
     *
     * @param htmlCodeResult 单文件 HTML 生成结果实体
     * @return 保存结果（含目录与文件名）
     */
    public static CodeSaveResult saveHtml(HtmlCodeResult htmlCodeResult) {
        return saveFiles(
                List.of(new CodeFile("index.html", htmlCodeResult.getHtmlCode())),
                CodeGenTypeEnum.HTML.getValue());
    }

    /**
     * 保存多文件（index.html + style.css + script.js）代码。
     *
     * @param result 多文件代码生成结果实体
     * @return 保存结果（含目录与文件名）
     */
    public static CodeSaveResult saveMultiFile(MultiFileCodeResult result) {
        return saveFiles(
                List.of(
                        new CodeFile("index.html", result.getHtmlCode()),
                        new CodeFile("style.css", result.getCssCode()),
                        new CodeFile("script.js", result.getJsCode())),
                CodeGenTypeEnum.MULTI_FILE.getValue());
    }

    /**
     * 通用保存：把一组代码文件写到唯一目录。
     *
     * @param files   代码文件列表（{@link CodeFile}）
     * @param bizType 业务类型（html / multi_file），用于目录命名
     * @return 保存结果（含目录与文件名）
     * @throws CodeSaveException 文件列表为空 / 目录创建失败 / 写盘异常
     */
    public static CodeSaveResult saveFiles(List<CodeFile> files, String bizType) {
        if (files == null || files.isEmpty()) {
            throw new CodeSaveException("没有可保存的代码文件");
        }
        String dirPath = buildUniqueDir(bizType);
        for (CodeFile file : files) {
            writeToFile(dirPath, file.name(), file.content());
        }
        return new CodeSaveResult(dirPath,
                files.stream().map(CodeFile::name).toList());
    }

    /**
     * 构建唯一文件夹路径。
     *
     * @param bizType 业务类型
     * @return 唯一目录的完整路径
     */
    private static String buildUniqueDir(String bizType) {
        String uniqueDirName = StrUtil.format("{}_{}", bizType, IdUtil.getSnowflakeNextIdStr());
        String dirPath = FILE_SAVE_ROOT_DIR + "/" + uniqueDirName;
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
