package com.cg.yangaicodemother.core.saver;

import java.io.File;
import java.util.List;

/**
 * 保存结果：文件写到了哪个目录、写了哪些文件。
 *
 * <p>与解析端的 {@link com.cg.yangaicodemother.core.parser.CodeParseResult} 相对应——
 * 解析器把模型输出归一化成一组 {@code CodeFile}，保存器把这一组文件落到磁盘后
 * 返回 {@link CodeSaveResult}，调用方拿 {@link #saveDir()} 拼下载 / 访问路径即可。
 *
 * <p>用 Java record 定义不可变值对象，省去 getter/setter 样板代码。
 *
 * @param saveDir   保存目录的绝对路径
 * @param fileNames 本次实际写盘的文件名列表（如 [index.html, style.css, script.js]）
 */
public record CodeSaveResult(String saveDir, List<String> fileNames) {

    /**
     * 保存目录的 {@link File} 对象，方便调用方做进一步的文件操作。
     */
    public File dir() {
        return new File(saveDir);
    }

    /**
     * 是否包含指定文件名（用于快速判断某个文件是否已生成）。
     */
    public boolean contains(String fileName) {
        return fileNames.contains(fileName);
    }
}
