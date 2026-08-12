package com.cg.yangaicodemother.core.editor;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HtmlStyleEditor 单测：样式直改（颜色/内边距/外边距）的确定性落盘逻辑。
 * 纯字符串处理，无 IO。
 */
class HtmlStyleEditorTest {

    private static Map<String, String> style(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void applyStyle_noExistingStyle_shouldInsertStyleAttribute() {
        String html = "<h1 class=\"hero\">标题</h1>";
        String out = HtmlStyleEditor.applyStyle(html, "h1", "", "标题", "hero", style("color", "#ff0000"));
        assertEquals("<h1 class=\"hero\" style=\"color:#ff0000\">标题</h1>", out);
    }

    @Test
    void applyStyle_existingStyle_shouldMergeKeepOtherProps() {
        String html = "<h1 class=\"hero\" style=\"font-size:20px;color:blue\">标题</h1>";
        String out = HtmlStyleEditor.applyStyle(html, "h1", "", "标题", "hero", style("color", "#ff0000"));
        // 覆盖 color，保留 font-size，其余不变
        assertTrue(out.contains("style=\"font-size:20px;color:#ff0000\""), out);
    }

    @Test
    void applyStyle_shouldAddPaddingAndMargin() {
        String html = "<button class=\"card-btn\">开始</button>";
        String out = HtmlStyleEditor.applyStyle(html, "button", "", "开始", "card-btn",
                style("padding", "8px", "margin", "10px"));
        assertEquals("<button class=\"card-btn\" style=\"padding:8px;margin:10px\">开始</button>", out);
    }

    @Test
    void applyStyle_byId_shouldLocateEvenWithSameTagElsewhere() {
        String html = "<h1>别的标题</h1><h1 id=\"main\">主标题</h1>";
        String out = HtmlStyleEditor.applyStyle(html, "h1", "main", "", "", style("color", "red"));
        assertEquals("<h1>别的标题</h1><h1 id=\"main\" style=\"color:red\">主标题</h1>", out);
    }

    @Test
    void applyStyle_nestedSameTag_shouldLocateInnerElement() {
        String html = "<div><div>外层</div><div>内层</div></div>";
        String out = HtmlStyleEditor.applyStyle(html, "div", "", "内层", "", style("padding", "5px"));
        // 只有含「内层」文本的最内层 div 被改
        assertEquals("<div><div>外层</div><div style=\"padding:5px\">内层</div></div>", out);
    }

    @Test
    void applyStyle_byClassToken_shouldLocateWhenNoText() {
        String html = "<div class=\"card hero\"></div><div class=\"card other\"></div>";
        String out = HtmlStyleEditor.applyStyle(html, "div", "", "", "card hero", style("margin", "2px"));
        // 注意：class 锚定取 className 的第一段，这里 className="card hero" 首段是 card，
        // 两个元素 class 都含 card，会命中第一个。这是兜底锚定的已知局限，靠 id/文本优先避免。
        assertTrue(out.contains("class=\"card hero\" style=\"margin:2px\""), out);
    }

    @Test
    void applyStyle_elementNotFound_shouldReturnNull() {
        String html = "<h1>标题</h1>";
        assertNull(HtmlStyleEditor.applyStyle(html, "h1", "", "不存在的文本", "", style("color", "red")));
        assertNull(HtmlStyleEditor.applyStyle(html, "p", "", "标题", "", style("color", "red")));
    }

    @Test
    void applyStyle_emptyProps_shouldReturnOriginal() {
        String html = "<h1>标题</h1>";
        assertEquals(html, HtmlStyleEditor.applyStyle(html, "h1", "", "标题", "", Map.of()));
    }

    @Test
    void applyStyle_mergeSerializesStably() {
        String html = "<h1 style=\"color:blue; padding:8px\">标题</h1>";
        String out = HtmlStyleEditor.applyStyle(html, "h1", "", "标题", "", style("padding", "12px"));
        assertTrue(out.contains("style=\"color:blue;padding:12px\""), out);
    }
}
