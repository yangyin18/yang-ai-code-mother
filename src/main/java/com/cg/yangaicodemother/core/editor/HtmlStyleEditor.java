package com.cg.yangaicodemother.core.editor;

import cn.hutool.core.util.StrUtil;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTML 样式直改工具：在源码里定位目标元素的开标签，把指定样式属性合并进其 style 属性。
 *
 * <p>可视化编辑「选中元素 → 改颜色/内边距/外边距 → 保存」的后端落盘实现（不调 AI）：
 * 只影响目标元素的 style 属性，元素本身其它属性、嵌套结构以及页面其它部分全部原样保留。
 * 定位锚点优先级：id &gt; 元素文本 &gt; class 首段；找不到目标元素返回 null，由调用方报错。
 *
 * <p>纯静态、无副作用，便于单元测试。生成的 HTML 由 AI 产出但经过 {@code CodeParser} 归一化，
 * 通常是小写标签 + 双引号属性的规整结构，按开标签文本定位足够可靠。
 */
public final class HtmlStyleEditor {

    private HtmlStyleEditor() {
    }

    /** style 属性匹配：style="..." 或 style='...'（取引号内内容到 group(2)/(3)） */
    private static final Pattern STYLE_ATTR = Pattern.compile(
            "style\\s*=\\s*(\"([^\"]*)\"|'([^']*)')", Pattern.CASE_INSENSITIVE);

    /**
     * 把指定样式属性写入目标元素的 style 属性，返回更新后的完整 HTML。
     *
     * @param html       整段 HTML 源码
     * @param tag        目标元素标签名（小写）
     * @param id         元素 id（非空则优先按 id 定位）
     * @param text       元素可见文本（id 为空时按文本定位）
     * @param className  元素 class 字符串（id/文本都为空时兜底按 class 首段定位）
     * @param styleProps 要修改的样式属性（键值；已存在的同名属性覆盖，其余内联样式保留）
     * @return 更新后的 HTML；找不到目标元素返回 null
     */
    public static String applyStyle(String html, String tag, String id, String text, String className,
                                    Map<String, String> styleProps) {
        if (StrUtil.isBlank(html) || StrUtil.isBlank(tag)
                || styleProps == null || styleProps.isEmpty()) {
            return html;
        }
        int[] openTag = locateOpenTag(html, tag.toLowerCase(), id, text, className);
        if (openTag == null) {
            return null;
        }
        int start = openTag[0];
        int end = openTag[1]; // '>' 之后一位
        String openTagText = html.substring(start, end);
        String merged = mergeInlineStyle(openTagText, styleProps);
        if (merged.equals(openTagText)) {
            return html;
        }
        return html.substring(0, start) + merged + html.substring(end);
    }

    /** 定位目标元素开标签区间 [start, end)，end 为开标签 '>' 之后一位；找不到返回 null */
    private static int[] locateOpenTag(String html, String tag, String id, String text, String className) {
        // 1. id 锚定（唯一，最稳）：<tag ... id="xxx" ...>
        if (StrUtil.isNotBlank(id)) {
            int idx = findElementByAttr(html, tag, "id", id);
            if (idx >= 0) {
                return openTagRange(html, idx);
            }
        }
        // 2. 文本锚定：定位文本，回溯到「最近的、未闭合的同 tag 开标签」
        if (StrUtil.isNotBlank(text)) {
            int p = html.indexOf(text);
            while (p >= 0) {
                int openIdx = findUnclosedOpenTagBefore(html, tag, p);
                if (openIdx >= 0) {
                    return openTagRange(html, openIdx);
                }
                p = html.indexOf(text, p + text.length());
            }
        }
        // 3. class 锚定：取 class 首段（无空格），匹配开标签 class 属性包含它
        if (StrUtil.isNotBlank(className)) {
            String first = className.trim().split("\\s+")[0];
            int idx = findElementByClassToken(html, tag, first);
            if (idx >= 0) {
                return openTagRange(html, idx);
            }
        }
        return null;
    }

    /** 扫描所有 &lt;tag ...&gt;，返回第一个「属性里含 attr="value" 或 attr='value'」的开标签起始下标 */
    private static int findElementByAttr(String html, String tag, String attr, String value) {
        Matcher m = Pattern.compile("<" + Pattern.quote(tag) + "\\b([^>]*)>").matcher(html);
        while (m.find()) {
            String attrs = m.group(1);
            if (attrs != null && containsAttrEquals(attrs, attr, value)) {
                return m.start();
            }
        }
        return -1;
    }

    /** 扫描所有 &lt;tag ...&gt;，返回第一个「class 属性包含指定 class token」的开标签起始下标 */
    private static int findElementByClassToken(String html, String tag, String token) {
        Matcher m = Pattern.compile("<" + Pattern.quote(tag) + "\\b([^>]*)>").matcher(html);
        while (m.find()) {
            String attrs = m.group(1);
            if (attrs != null && classAttrContainsToken(attrs, token)) {
                return m.start();
            }
        }
        return -1;
    }

    /** 在属性串里匹配 attr="value" 或 attr='value'（值整体等于 value，双引号/单引号都可） */
    private static boolean containsAttrEquals(String attrs, String attr, String value) {
        Matcher m = Pattern.compile("\\b" + Pattern.quote(attr) + "\\s*=\\s*(\"([^\"]*)\"|'([^']*)')")
                .matcher(attrs);
        while (m.find()) {
            String inner = m.group(2) != null ? m.group(2) : m.group(3);
            if (value.equals(inner)) {
                return true;
            }
        }
        return false;
    }

    /** 在属性串里判断 class 属性的值是否包含指定 token（按空白拆分） */
    private static boolean classAttrContainsToken(String attrs, String token) {
        Matcher m = Pattern.compile("\\bclass\\s*=\\s*(\"([^\"]*)\"|'([^']*)')", Pattern.CASE_INSENSITIVE)
                .matcher(attrs);
        while (m.find()) {
            String inner = m.group(2) != null ? m.group(2) : m.group(3);
            if (inner != null) {
                for (String t : inner.trim().split("\\s+")) {
                    if (token.equals(t)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 文本锚定：找 position 之前「最近一个未被闭合的同 tag 开标签」。
     * 正向扫描 [0,pos)，遇到 &lt;tag&gt; 记录位置、遇到 &lt;/tag&gt; 清零——
     * 结束时的 lastOpen 就是包含该文本的、最内层未闭合的同 tag 元素的开标签。
     */
    private static int findUnclosedOpenTagBefore(String html, String tag, int pos) {
        Matcher m = Pattern.compile("<(/?)" + Pattern.quote(tag) + "\\b").matcher(html.substring(0, pos));
        int lastOpen = -1;
        while (m.find()) {
            if (m.group(1).isEmpty()) {
                lastOpen = m.start();
            } else {
                lastOpen = -1;
            }
        }
        return lastOpen;
    }

    /** 从开标签起始下标取区间 [start, '>' 之后一位) */
    private static int[] openTagRange(String html, int openStart) {
        int gt = html.indexOf('>', openStart);
        if (gt < 0) {
            return null;
        }
        return new int[]{openStart, gt + 1};
    }

    /** 把样式属性合并进开标签：已有 style 属性则合并覆盖，没有则插到 '>' 之前 */
    private static String mergeInlineStyle(String openTagText, Map<String, String> styleProps) {
        Matcher sm = STYLE_ATTR.matcher(openTagText);
        if (sm.find()) {
            String old = sm.group(2) != null ? sm.group(2) : sm.group(3);
            Map<String, String> merged = parseInlineStyle(old);
            merged.putAll(styleProps);
            return openTagText.substring(0, sm.start())
                    + "style=\"" + serializeInlineStyle(merged) + "\""
                    + openTagText.substring(sm.end());
        }
        // 没有 style 属性：插入到开标签末尾 '>' 之前
        int gt = openTagText.lastIndexOf('>');
        if (gt < 0) {
            return openTagText;
        }
        return openTagText.substring(0, gt)
                + " style=\"" + serializeInlineStyle(styleProps) + "\""
                + openTagText.substring(gt);
    }

    /** 解析内联样式 "color:red; padding:8px" → 有序 map（保序，便于稳定序列化） */
    private static Map<String, String> parseInlineStyle(String style) {
        Map<String, String> map = new LinkedHashMap<>();
        if (StrUtil.isBlank(style)) {
            return map;
        }
        for (String part : style.split(";")) {
            int ci = part.indexOf(':');
            if (ci > 0) {
                String k = part.substring(0, ci).trim();
                String v = part.substring(ci + 1).trim();
                if (!k.isEmpty()) {
                    map.put(k, v);
                }
            }
        }
        return map;
    }

    /** 有序 map → "color:red;padding:8px"（统一小写键，值原样） */
    private static String serializeInlineStyle(Map<String, String> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : map.entrySet()) {
            String v = e.getValue() == null ? "" : e.getValue().trim();
            if (v.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(e.getKey().toLowerCase()).append(':').append(v);
        }
        return sb.toString();
    }

}
