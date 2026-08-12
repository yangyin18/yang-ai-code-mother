package com.cg.yangaicodemother.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CodeSanitizer 单元测试：验证生成代码里的一切外部链接 / 跳转都被移除，
 * 同时站内相对引用（style.css / script.js）与内联自包含资源（data:）得以保留。
 */
class CodeSanitizerTest {

    // ==================== HTML ====================

    @Test
    void html_shouldRemoveAnchorTagsKeepText() {
        String in = "<p>说明<a href=\"https://evil.com\">点我</a>结束</p>";
        String out = CodeSanitizer.sanitizeHtml(in);
        assertFalse(out.contains("<a"), "应移除 <a> 标签");
        assertFalse(out.contains("</a>"), "应移除 </a> 标签");
        assertFalse(out.contains("evil.com"), "不应出现外部域名");
        assertEquals("<p>说明点我结束</p>", out, "应保留锚点内部文本");
    }

    @Test
    void html_shouldRemoveAbsoluteSrcAttr() {
        String in = "<img src=\"https://picsum.photos/200/300\" alt=\"图\">";
        String out = CodeSanitizer.sanitizeHtml(in);
        assertFalse(out.contains("picsum.photos"), "应移除外部图片地址");
        assertFalse(out.contains("src="), "应移除整个 src 属性");
    }

    @Test
    void html_shouldRemoveExternalScriptAndLink() {
        String in = "<script src=\"https://cdn.example.com/lib.js\"></script>"
                + "<link rel=\"stylesheet\" href=\"https://fonts.googleapis.com/css\">";
        String out = CodeSanitizer.sanitizeHtml(in);
        assertFalse(out.contains("cdn.example.com"), "应移除外部脚本");
        assertFalse(out.contains("fonts.googleapis.com"), "应移除外部字体");
        assertFalse(out.contains("https://"), "不应残留任何 https 地址");
    }

    @Test
    void html_shouldKeepRelativeReferences() {
        String in = "<link rel=\"stylesheet\" href=\"style.css\">"
                + "<script src=\"script.js\"></script>"
                + "<img src=\"data:image/svg+xml,%3Csvg%3E%3C/svg%3E\" alt=\"内联\">";
        String out = CodeSanitizer.sanitizeHtml(in);
        assertEquals(in, out, "站内相对引用与 data: 自包含资源应原样保留");
    }

    @Test
    void html_shouldRemoveMetaRefreshRedirect() {
        String in = "<meta http-equiv=\"refresh\" content=\"0;url=https://evil.com\">正文";
        String out = CodeSanitizer.sanitizeHtml(in);
        assertFalse(out.contains("http-equiv"), "应移除 refresh meta");
        assertFalse(out.contains("evil.com"), "不应残留跳转地址");
        assertEquals("正文", out.trim());
    }

    @Test
    void html_shouldRemoveBaseTag() {
        String in = "<head><base href=\"https://evil.com/\"></head>";
        String out = CodeSanitizer.sanitizeHtml(in);
        assertFalse(out.contains("<base"), "应移除 <base> 标签");
        assertFalse(out.contains("evil.com"));
    }

    @Test
    void html_shouldNeutralizeInlineStyleUrl() {
        String in = "<div style=\"background:url('https://img.example.com/a.png')\">x</div>";
        String out = CodeSanitizer.sanitizeHtml(in);
        assertFalse(out.contains("img.example.com"), "应中和行内样式外部 url");
        assertFalse(out.contains("https://"));
    }

    @Test
    void html_shouldRemoveJavaScriptHref() {
        String in = "<a href=\"javascript:alert(1)\">x</a>";
        String out = CodeSanitizer.sanitizeHtml(in);
        assertFalse(out.contains("<a"), "javascript: 链接应随 <a> 一起移除");
    }

    // ==================== CSS ====================

    @Test
    void css_shouldRemoveExternalUrlAndImport() {
        String in = "body{background:url('https://cdn.example.com/bg.png');}\n"
                + "@import url(https://fonts.example.com/font.css);\n"
                + ".a{color:red;}";
        String out = CodeSanitizer.sanitizeCss(in);
        assertFalse(out.contains("cdn.example.com"));
        assertFalse(out.contains("fonts.example.com"));
        assertFalse(out.contains("@import"), "应移除外部 @import");
        assertFalse(out.contains("url(https"), "外部 url 应被中和为 none");
        assertTrue(out.contains(".a{color:red;}"), "本地样式应保留");
    }

    @Test
    void css_shouldKeepRelativeAndDataUrls() {
        String in = ".a{background:url(./bg.png);}.b{background:url(data:image/png;base64,AAAA);}";
        assertEquals(in, CodeSanitizer.sanitizeCss(in), "相对路径与 data: url 应保留");
    }

    // ==================== JS ====================

    @Test
    void js_shouldNeutralizeAbsoluteUrlLiterals() {
        String in = "location.href = \"https://evil.com/redirect\";\n"
                + "window.open('https://spam.com');\n"
                + "fetch(\"https://api.example.com/data\").then(r=>r.json());";
        String out = CodeSanitizer.sanitizeJs(in);
        assertFalse(out.contains("evil.com"));
        assertFalse(out.contains("spam.com"));
        assertFalse(out.contains("api.example.com"));
        assertFalse(out.contains("location.href ="), "location.href 赋值应被阻断");
        assertFalse(out.contains("https://"), "不应残留 https 地址");
    }

    @Test
    void js_shouldKeepPlainStringsAndLogic() {
        String in = "const name='hello';console.log(name);let x=1+2;";
        assertEquals(in, CodeSanitizer.sanitizeJs(in), "普通字符串与逻辑应原样保留");
    }

    // ==================== 幂等 ====================

    @Test
    void sanitize_shouldBeIdempotent() {
        String in = "<a href=\"https://x.com\">y</a>"
                + "<style>.a{background:url(https://cdn.com/a.png)}</style>"
                + "<script>location.href='https://z.com'</script>";
        String once = CodeSanitizer.sanitizeHtml(in);
        String twice = CodeSanitizer.sanitizeHtml(once);
        assertEquals(once, twice, "二次净化结果应一致");
    }
}
