package com.cg.yangaicodemother.core.parser;

import java.util.List;

/**
 * 解析结果：一组代码文件 + 可选的描述。
 *
 * <p>无论模型输出什么格式（JSON / Markdown / 裸文本），
 * {@link CodeParser#parse} 都会返回一个结构化的 {@link CodeParseResult}，
 * 调用方拿到 {@link #files()} 落盘、拿 {@link #description()} 展示给用户即可。
 *
 * @param files       解析出的代码文件，至少一个且内容非空
 * @param description 模型对生成结果的描述（Markdown 模式下可能为 null）
 */
public record CodeParseResult(List<CodeFile> files, String description) {

    /**
     * 取指定文件名的内容；不存在时返回 null。
     */
    public String fileContent(String fileName) {
        return files.stream()
                .filter(f -> f.name().equals(fileName))
                .findFirst()
                .map(CodeFile::content)
                .orElse(null);
    }
}
