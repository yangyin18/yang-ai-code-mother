package com.cg.yangaicodemother.core.parser;

/**
 * 一个待落盘的代码文件。
 *
 * <p>这是整个解析器的「原子单位」：不管模型输出的是 JSON 还是 Markdown，
 * 最终都被归一化成若干个 {@code (文件名, 文件内容)} 对，
 * 供 {@link com.cg.yangaicodemother.core.saver.CodeSaver} 直接写盘。
 *
 * <p>用 Java record 定义不可变值对象，省去 getter/setter、equals/hashCode 样板代码。
 *
 * @param name    文件名，如 index.html / style.css / script.js
 * @param content 文件内容
 */
public record CodeFile(String name, String content) {
}
