package com.cg.yangaicodemother.core.parser;

import cn.hutool.core.util.StrUtil;
import com.cg.yangaicodemother.model.enums.CodeGenTypeEnum;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 代码解析器：把大模型返回的「任意形态」文本解析成结构化代码文件。
 *
 * <p>为什么要写这个类：大模型不总是听话地返回严格 JSON。同样的需求，
 * 它可能返回——严格 JSON、```json 围栏包着的 JSON、前面带解释的 JSON、
 * 带语言标签的 Markdown 代码块（```html）、带文件名标签的代码块
 * （**index.html** + ```html）、裸的一段 HTML。全部都能被本解析器吃掉。
 *
 * <p>解析策略是「逐级降级」的链条，命中即返回：
 * <pre>
 *   ① JSON 优先：直接解析 / 剥掉围栏解析 / 从解释文本里抠出第一个 JSON 对象
 *   ② Markdown：提取 ``` 围栏代码块，按「文件名标签 → 语言标签 → 出现顺序」给文件命名
 *   ③ 裸 HTML：文本本身长得像 HTML，整体作为 index.html
 *   ④ 全都不行 → 抛 {@link CodeParserException}
 * </pre>
 *
 * <p>使用方式（静态方法，无状态）：
 * <pre>{@code
 * CodeParseResult result = CodeParser.parse(rawText, CodeGenTypeEnum.MULTI_FILE);
 * for (CodeFile file : result.files()) {
 *     FileUtil.writeUtf8String(file.content(), dir + "/" + file.name());
 * }
 * }</pre>
 */
public final class CodeParser {

    private CodeParser() {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 三个代码文件的规范文件名 */
    private static final List<String> KNOWN_FILENAMES =
            List.of("index.html", "style.css", "script.js");

    /** Markdown 围栏代码块：```lang 换行 内容 ```（DOTALL 让 . 也能匹配换行） */
    private static final Pattern FENCE =
            Pattern.compile("```[ \\t]*(\\w*)[ \\t]*\\r?\\n(.*?)```", Pattern.DOTALL);

    /** 语言标签 → 文件名（没有文件名标签时的兜底） */
    private static String fileNameByLanguage(String lang) {
        if (lang == null || lang.isEmpty()) {
            return null;
        }
        String l = lang.toLowerCase();
        if (l.startsWith("html")) {
            return "index.html";
        }
        if (l.equals("css")) {
            return "style.css";
        }
        if (l.equals("js") || l.equals("javascript") || l.equals("jsx")) {
            return "script.js";
        }
        return null;
    }

    // ==================== 入口 ====================

    /**
     * 解析大模型原始输出。
     *
     * @param rawText 模型返回的完整文本
     * @param type    生成类型，用于 Markdown 无标签时按顺序分配文件名
     * @return 解析结果（至少一个非空代码文件）
     * @throws CodeParserException 空输入 / 无法解析出任何代码
     */
    public static CodeParseResult parse(String rawText, CodeGenTypeEnum type) {
        if (StrUtil.isBlank(rawText)) {
            throw new CodeParserException("模型输出为空，无法解析");
        }

        // ① JSON：大多数时候模型会遵守 prompt 里的严格 JSON 约定
        CodeParseResult jsonResult = tryParseJson(rawText);
        if (jsonResult != null) {
            return jsonResult;
        }

        // ② Markdown：不守规矩时的常见形态
        CodeParseResult markdownResult = tryParseMarkdown(rawText, type);
        if (markdownResult != null) {
            return markdownResult;
        }

        // ③ 裸 HTML：文本整体就是页面
        String trimmed = rawText.trim();
        if (looksLikeHtml(trimmed)) {
            return new CodeParseResult(List.of(new CodeFile("index.html", trimmed)), null);
        }

        throw new CodeParserException("未能从模型输出中解析出任何代码");
    }

    // ==================== 策略①：JSON ====================

    private static CodeParseResult tryParseJson(String rawText) {
        // 1) 模型可能把 JSON 包在 ```json ... ``` 围栏里 → 先剥围栏
        String candidate = unwrapJsonFence(rawText);

        // 2) 若剥完仍不以 { 开头，说明前面有解释文字 → 从文本里抠出第一个 JSON 对象
        String jsonText = candidate.startsWith("{")
                ? candidate
                : extractJsonObject(candidate);
        if (jsonText == null) {
            return null;
        }

        try {
            JsonNode node = MAPPER.readTree(jsonText);
            if (node == null || !node.isObject() || !node.has("htmlCode")) {
                return null; // 解析出来但不是我们的格式，交给下一级策略
            }

            List<CodeFile> files = new ArrayList<>();
            // 顺序固定，保证多文件时 index.html → style.css → script.js
            addIfPresent(node, files, "htmlCode", "index.html");
            addIfPresent(node, files, "cssCode", "style.css");
            addIfPresent(node, files, "jsCode", "script.js");
            if (files.isEmpty()) {
                return null; // 关键字段全空，无有效代码
            }

            String description = node.has("description") && node.get("description").isTextual()
                    ? node.get("description").asText()
                    : null;
            return new CodeParseResult(files, description);
        } catch (Exception e) {
            // 不是合法 JSON（比如 CSS 里的 body{margin:0} 被误当 JSON 抠出来）
            return null;
        }
    }

    /** 若整个文本恰好是单个 ```json 围栏，返回围栏内内容；否则原样返回 */
    private static String unwrapJsonFence(String text) {
        Matcher matcher = FENCE.matcher(text.trim());
        if (matcher.matches() && matcher.group(1).equalsIgnoreCase("json")) {
            return matcher.group(2).trim();
        }
        return text.trim();
    }

    /**
     * 从任意文本里抠出「第一个完整配对的 JSON 对象」。
     *
     * <p>手工做括号配对是因为要容忍前后有解释性文字。要注意：
     * 跳过字符串里的 { } ——字符串可能含花括号（如 CSS 内容），
     * 这里通过 inString 状态来识别字符串边界。
     */
    private static String extractJsonObject(String text) {
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private static void addIfPresent(JsonNode node, List<CodeFile> files,
                                     String field, String fileName) {
        JsonNode value = node.get(field);
        if (value != null && value.isTextual() && !value.asText().trim().isEmpty()) {
            files.add(new CodeFile(fileName, value.asText().trim()));
        }
    }

    // ==================== 策略②：Markdown ====================

    private static CodeParseResult tryParseMarkdown(String rawText, CodeGenTypeEnum type) {
        Matcher matcher = FENCE.matcher(rawText);
        List<CodeFile> files = new ArrayList<>();
        int order = 0; // 无标签、无语言时按第几个代码块分配文件名

        while (matcher.find()) {
            String lang = matcher.group(1).toLowerCase();
            String content = matcher.group(2).trim();
            if (content.isEmpty()) {
                continue; // 空代码块无意义
            }

            // 模型把 JSON 包进 ```json 围栏 → 递归走 JSON 策略
            if (lang.equals("json")) {
                CodeParseResult inner = tryParseJson(content);
                if (inner != null) {
                    return inner;
                }
                continue;
            }

            String name = resolveFileName(rawText.substring(0, matcher.start()), lang, type, order);
            if (name != null) {
                files.add(new CodeFile(name, content));
                order++;
            }
        }

        if (files.isEmpty()) {
            return null;
        }
        return new CodeParseResult(files, null); // Markdown 模式没有 description
    }

    /**
     * 决定一个代码块该叫什么文件名，优先级：
     * ① 代码块上方紧挨着的文件名标签（**index.html** / index.html:）
     * ② 语言标签（```html → index.html）
     * ③ 都拿不到时按出现顺序（第 1 个→index.html，第 2 个→style.css，第 3 个→script.js）
     *
     * @param beforeText 代码块之前的所有文本（用于找文件名标签）
     * @param lang       围栏语言标签，可能为空
     * @param type       生成类型（HTML 只认 index.html）
     * @param order      这是第几个代码块（从 0 开始）
     */
    private static String resolveFileName(String beforeText, String lang,
                                          CodeGenTypeEnum type, int order) {
        // ① 文件名标签：只看围栏前最近一小段文本
        String recent = beforeText.length() > 300
                ? beforeText.substring(beforeText.length() - 300)
                : beforeText;
        for (String name : KNOWN_FILENAMES) {
            // 匹配如：**index.html** / index.html / index.html: （含结尾换行）
            Pattern label = Pattern.compile(
                    "(?s).*(\\*\\*)?\\Q" + name + "\\E(\\*\\*)?[ \\t]*:?[ \\t]*$");
            if (label.matcher(recent).matches()) {
                return name;
            }
        }

        // ② HTML 单文件模式：第 1 个代码块整体就是 index.html。
        //    此时语言提示不可靠（模型可能输出 ```css 但内容其实是页面），
        //    直接按「第几个代码块」来定更稳。
        if (type == CodeGenTypeEnum.HTML) {
            return order == 0 ? "index.html" : null;
        }

        // ③ 多文件模式：先看语言标签
        String byLang = fileNameByLanguage(lang);
        if (byLang != null) {
            return byLang;
        }

        // ④ 多文件模式：无标签无语言时按出现顺序兜底
        return switch (order) {
            case 0 -> "index.html";
            case 1 -> "style.css";
            case 2 -> "script.js";
            default -> null;
        };
    }

    // ==================== 策略③：裸 HTML ====================

    private static boolean looksLikeHtml(String text) {
        String t = text.trim().toLowerCase();
        return t.startsWith("<!doctype")
                || t.startsWith("<html")
                || (t.startsWith("<") && t.contains("</html>"));
    }
}
