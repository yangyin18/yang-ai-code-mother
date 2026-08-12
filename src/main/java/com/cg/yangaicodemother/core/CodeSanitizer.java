package com.cg.yangaicodemother.core;

import cn.hutool.core.util.StrUtil;
import com.cg.yangaicodemother.ai.model.HtmlCodeResult;
import com.cg.yangaicodemother.ai.model.MultiFileCodeResult;
import com.cg.yangaicodemother.core.parser.CodeFile;
import com.cg.yangaicodemother.core.parser.CodeParseResult;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 代码净化器：把 AI 生成的代码里一切「外部链接 / 跳转」移除，保证生成的应用自包含、
 * 不会跳转到任何外部平台（安全兜底，defense in depth）。
 *
 * <p>为什么需要它：大模型不一定 100% 遵守 prompt 里的「禁止外部依赖」约束，
 * 可能输出 {@code <a href="https://...">}、外部 CDN / 图片地址、
 * CSS {@code url(https://...)}、JS {@code location.href = "https://..."}、
 * {@code <meta http-equiv="refresh" content="0;url=...">} 跳转等。
 * 本类在代码返回给前端 / 落盘 / 部署之前统一净化。
 *
 * <p>净化原则：只删除「绝对地址」与「跳转结构」，保留站内相对引用
 * （如 {@code <link href="style.css">}、{@code <script src="script.js">}），
 * 保证生成的应用仍能正常预览与部署。
 */
public final class CodeSanitizer {

    private CodeSanitizer() {
    }

    /** 绝对地址前缀（大小写不敏感），命中即视为外部链接。data: 仅允许出现在 src（内联自包含资源） */
    private static final Pattern ABS_URL_PREFIX = Pattern.compile(
            "^(?:(?:https?|ftp):)?//|^www\\.|^javascript:|^vbscript:",
            Pattern.CASE_INSENSITIVE);

    /** <a ...> / </a> 全部移除：锚点本身就是链接结构 */
    private static final Pattern ANCHOR_TAG = Pattern.compile("(?i)<\\s*/?\\s*a\\b[^>]*>");

    /** <base href="https://..."> 会重写所有相对地址，直接整体移除 */
    private static final Pattern BASE_TAG = Pattern.compile("(?i)<\\s*base\\b[^>]*>");

    /** 可能携带 URL 的 HTML 属性：仅当值为绝对地址时删除该属性 */
    private static final Pattern URL_ATTR = Pattern.compile(
            "(?i)\\s+(href|src|action|poster|cite|formaction|background|codebase|longdesc|usemap|archive)\\s*=\\s*"
                    + "(\"[^\"]*\"|'[^']*'|[^\\s>]+)");

    /** <meta ...> 标签：含 refresh / url= 的整标签移除（页面跳转） */
    private static final Pattern META_TAG = Pattern.compile("(?i)<\\s*meta\\b[^>]*>");

    /** CSS / 行内样式的 url(绝对地址)，替换为 none（相对与 data: 保留） */
    private static final Pattern CSS_URL = Pattern.compile(
            "(?i)url\\s*\\(\\s*['\"]?(?:(?:https?|ftp):)?(?://|www\\.)[^)'\"]*['\"]?\\s*\\)");

    /** CSS @import 外部样式：整行移除 */
    private static final Pattern AT_IMPORT = Pattern.compile(
            "(?im)^[ \\t]*@import[ \\t]+[^\\n;]*?(?:(?:https?|ftp):)?(?://|www\\.)[^\\n;]*;?[ \\t]*$");

    /** JS 字符串字面量里的绝对地址（"https://..." / 'https://...' / `https://...`），替换为空串 */
    private static final Pattern JS_ABS_URL_LITERAL = Pattern.compile(
            "([\"'`])(?:(?:https?|ftp):)?(?://|www\\.)[^\"'`\\n]*\\1");

    /** JS 地址栏跳转赋值：location.href = / location.replace( 等整段替换为注释，阻断任何跳转 */
    private static final Pattern LOCATION_NAV = Pattern.compile(
            "(?i)\\b(?:window\\.)?location\\s*\\.\\s*(?:href|replace|assign)\\s*=\\s*");

    // ==================== 入口 ====================

    /** 按文件名分派净化；未知扩展名按 HTML 处理（最常用） */
    public static String sanitize(String code, String fileName) {
        if (StrUtil.isBlank(code)) {
            return code;
        }
        String name = fileName == null ? "" : fileName.toLowerCase();
        if (name.endsWith(".css")) {
            return sanitizeCss(code);
        }
        if (name.endsWith(".js")) {
            return sanitizeJs(code);
        }
        return sanitizeHtml(code);
    }

    /** 净化解析结果里的每一个代码文件 */
    public static CodeParseResult sanitize(CodeParseResult result) {
        if (result == null) {
            return null;
        }
        List<CodeFile> files = result.files().stream()
                .map(f -> new CodeFile(f.name(), sanitize(f.content(), f.name())))
                .toList();
        return new CodeParseResult(files, result.description());
    }

    /** 净化单文件 HTML 结果（非流式路径） */
    public static HtmlCodeResult sanitize(HtmlCodeResult result) {
        if (result == null) {
            return null;
        }
        result.setHtmlCode(sanitizeHtml(result.getHtmlCode()));
        return result;
    }

    /** 净化多文件结果（非流式路径） */
    public static MultiFileCodeResult sanitize(MultiFileCodeResult result) {
        if (result == null) {
            return null;
        }
        result.setHtmlCode(sanitizeHtml(result.getHtmlCode()));
        result.setCssCode(sanitizeCss(result.getCssCode()));
        result.setJsCode(sanitizeJs(result.getJsCode()));
        return result;
    }

    // ==================== HTML ====================

    public static String sanitizeHtml(String html) {
        if (StrUtil.isBlank(html)) {
            return html;
        }
        String s = html;

        // 1) <a> 锚点整体移除（含 </a>，保留内部文本）
        s = ANCHOR_TAG.matcher(s).replaceAll("");

        // 2) <base> 标签整体移除（会改写相对地址）
        s = BASE_TAG.matcher(s).replaceAll("");

        // 3) 绝对地址属性（src/href/action...）删掉该属性；data: 仅允许留在 src（内联自包含资源）
        Matcher attrMatcher = URL_ATTR.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (attrMatcher.find()) {
            String attrName = attrMatcher.group(1);
            String value = attrMatcher.group(2);
            String bare = (value.length() >= 2 && (value.startsWith("\"") || value.startsWith("'")))
                    ? value.substring(1, value.length() - 1).trim()
                    : value.trim();
            boolean isExternal = ABS_URL_PREFIX.matcher(bare).find();
            boolean keepDataSrc = attrName.equalsIgnoreCase("src")
                    && bare.toLowerCase().startsWith("data:");
            if (isExternal && !keepDataSrc) {
                attrMatcher.appendReplacement(sb, ""); // 删掉该属性
            } else {
                attrMatcher.appendReplacement(sb, Matcher.quoteReplacement(attrMatcher.group()));
            }
        }
        attrMatcher.appendTail(sb);
        s = sb.toString();

        // 4) <meta http-equiv="refresh" ... url=...> 页面跳转整标签移除
        Matcher metaMatcher = META_TAG.matcher(s);
        sb = new StringBuffer();
        while (metaMatcher.find()) {
            String tag = metaMatcher.group();
            String low = tag.toLowerCase();
            boolean isRefresh = low.contains("http-equiv") && low.contains("refresh");
            boolean hasUrl = low.contains("url=") || low.contains("url:") || low.contains("0;");
            if (isRefresh || hasUrl) {
                metaMatcher.appendReplacement(sb, "");
            } else {
                metaMatcher.appendReplacement(sb, Matcher.quoteReplacement(tag));
            }
        }
        metaMatcher.appendTail(sb);
        s = sb.toString();

        // 5) 行内 / <style> 里的 url(绝对地址) → none
        s = CSS_URL.matcher(s).replaceAll("none");

        return s;
    }

    // ==================== CSS ====================

    public static String sanitizeCss(String css) {
        if (StrUtil.isBlank(css)) {
            return css;
        }
        String s = AT_IMPORT.matcher(css).replaceAll("");
        s = CSS_URL.matcher(s).replaceAll("none");
        return s;
    }

    // ==================== JS ====================

    public static String sanitizeJs(String js) {
        if (StrUtil.isBlank(js)) {
            return js;
        }
        // 1) 字符串字面量里的绝对地址 → 空串（fetch/window.open/img.src/location.href 传 url 全被中和）
        String s = JS_ABS_URL_LITERAL.matcher(js).replaceAll("\"\"");
        // 2) location.href/replace/assign 赋值 → 注释（阻断任何跳转意图）
        s = LOCATION_NAV.matcher(s).replaceAll("/* nav-blocked */ ");
        return s;
    }
}
