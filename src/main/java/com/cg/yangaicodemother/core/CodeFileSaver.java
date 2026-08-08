package com.cg.yangaicodemother.core;

import com.cg.yangaicodemother.ai.model.HtmlCodeResult;
import com.cg.yangaicodemother.ai.model.MultiFileCodeResult;
import com.cg.yangaicodemother.core.parser.CodeFile;
import com.cg.yangaicodemother.core.saver.CodeSaver;

import java.io.File;
import java.util.List;

/**
 * 文件保存（兼容层）。
 *
 * <p>真正的保存实现已迁到 {@link com.cg.yangaicodemother.core.saver.CodeSaver}，
 * 本类仅保留旧的静态方法签名（返回目录 {@link File}），把调用转发给
 * {@link CodeSaver} 后取其目录对象，避免破坏 {@link CodeGenFacade} 与既有调用方。
 * 新代码请直接使用 {@link CodeSaver}（返回结构化的 {@code CodeSaveResult}）。
 *
 * @deprecated 请改用 {@link CodeSaver}
 */
@Deprecated
public class CodeFileSaver {

    private CodeFileSaver() {
    }

    /**
     * 保存单文件 HTML 代码。
     *
     * @param htmlCodeResult html代码实体
     * @return 生成的文件夹文件对象
     * @see CodeSaver#saveHtml(HtmlCodeResult)
     */
    public static File saveHtmlCodeResult(HtmlCodeResult htmlCodeResult) {
        return CodeSaver.saveHtml(htmlCodeResult).dir();
    }

    /**
     * 保存多文件(html、css、js)代码结果。
     *
     * @param result 多文件代码返回实体
     * @return 生成的文件夹对象
     * @see CodeSaver#saveMultiFile(MultiFileCodeResult)
     */
    public static File saveMultiFileCodeResult(MultiFileCodeResult result) {
        return CodeSaver.saveMultiFile(result).dir();
    }

    /**
     * 通用保存：把解析器产出的一组代码文件写到唯一目录。
     *
     * @param files   代码文件列表（{@link com.cg.yangaicodemother.core.parser.CodeFile}）
     * @param bizType 业务类型（html / multi_file），用于目录命名
     * @return 保存目录文件对象
     * @see CodeSaver#saveFiles(List, String)
     */
    public static File saveFiles(List<CodeFile> files, String bizType) {
        return CodeSaver.saveFiles(files, bizType).dir();
    }
}
