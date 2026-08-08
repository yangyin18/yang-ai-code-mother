package com.cg.yangaicodemother.core.parser;

import com.cg.yangaicodemother.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * CodeParser 单元测试：覆盖「JSON → Markdown → 裸 HTML」三级策略与全部兜底规则。
 *
 * <p>这些用例就是解析器行为的「说明书」：模型再乱输出，
 * 只要最终能落到其中一类，都应该解析成功。
 */
class CodeParserTest {

    // ==================== 策略①：JSON ====================

    @Test
    void parse_jsonHtml_returnsSingleFile() {
        CodeParseResult result = CodeParser.parse(
                "{\"htmlCode\":\"<html><body>hi</body></html>\",\"description\":\"一个博客\"}",
                CodeGenTypeEnum.HTML);
        assertEquals(List.of(new CodeFile("index.html", "<html><body>hi</body></html>")), result.files());
        assertEquals("一个博客", result.description());
    }

    @Test
    void parse_jsonMulti_returnsThreeFilesInOrder() {
        CodeParseResult result = CodeParser.parse(
                "{\"htmlCode\":\"<html>\",\"cssCode\":\"body{}\",\"jsCode\":\"js()\"}",
                CodeGenTypeEnum.MULTI_FILE);
        assertEquals(3, result.files().size());
        assertEquals("index.html", result.files().get(0).name());
        assertEquals("style.css", result.files().get(1).name());
        assertEquals("script.js", result.files().get(2).name());
    }

    @Test
    void parse_jsonWrappedInFence_parses() {
        String text = """
                ```json
                {"htmlCode":"<html>a</html>","description":"围栏里的JSON"}
                ```
                """;
        CodeParseResult result = CodeParser.parse(text, CodeGenTypeEnum.HTML);
        assertEquals(new CodeFile("index.html", "<html>a</html>"), result.files().get(0));
        assertEquals("围栏里的JSON", result.description());
    }

    @Test
    void parse_jsonWithExplanations_parses() {
        String text = "好的，这是你要的页面：\n"
                + "{\"htmlCode\":\"<html>b</html>\",\"description\":\"带解释\"}\n"
                + "希望你喜欢！";
        CodeParseResult result = CodeParser.parse(text, CodeGenTypeEnum.HTML);
        assertEquals("<html>b</html>", result.files().get(0).content());
        assertEquals("带解释", result.description());
    }

    // ==================== 策略②：Markdown ====================

    @Test
    void parse_markdownSingleHtmlBlock_usesFirstBlockAsIndexHtml() {
        String text = """
                生成好了：
                ```html
                <!DOCTYPE html>
                <html>md</html>
                ```
                """;
        CodeParseResult result = CodeParser.parse(text, CodeGenTypeEnum.HTML);
        assertEquals(
                new CodeFile("index.html", "<!DOCTYPE html>\n<html>md</html>"),
                result.files().get(0));
        assertNull(result.description());
    }

    @Test
    void parse_markdownWithFilenameLabels_mapsToFiles() {
        String text = """
                **index.html**
                ```html
                <html>h</html>
                ```
                **style.css**
                ```css
                body { color: red; }
                ```
                **script.js**
                ```js
                console.log(1);
                ```
                """;
        CodeParseResult result = CodeParser.parse(text, CodeGenTypeEnum.MULTI_FILE);
        assertEquals(List.of(
                new CodeFile("index.html", "<html>h</html>"),
                new CodeFile("style.css", "body { color: red; }"),
                new CodeFile("script.js", "console.log(1);")), result.files());
    }

    @Test
    void parse_markdownMulti_withLanguageHints_mapsToFiles() {
        String text = """
                ```html
                <html>h</html>
                ```
                ```css
                body{}
                ```
                ```js
                js()
                ```
                """;
        CodeParseResult result = CodeParser.parse(text, CodeGenTypeEnum.MULTI_FILE);
        assertEquals(List.of(
                new CodeFile("index.html", "<html>h</html>"),
                new CodeFile("style.css", "body{}"),
                new CodeFile("script.js", "js()")), result.files());
    }

    @Test
    void parse_markdownMulti_unspecifiedOrder_fallsBackByOrder() {
        String text = """
                ```
                <html>h</html>
                ```
                ```
                body{}
                ```
                ```
                js()
                ```
                """;
        CodeParseResult result = CodeParser.parse(text, CodeGenTypeEnum.MULTI_FILE);
        assertEquals(List.of(
                new CodeFile("index.html", "<html>h</html>"),
                new CodeFile("style.css", "body{}"),
                new CodeFile("script.js", "js()")), result.files());
    }

    @Test
    void parse_markdownCssFirst_htmlType_stillMapsFirstBlockToIndex() {
        // HTML 单文件模式：即使模型先给了一个 css 块，也作为 index.html 处理
        String text = """
                ```css
                body{margin:0}
                ```
                ```html
                <html>x</html>
                ```
                """;
        CodeParseResult result = CodeParser.parse(text, CodeGenTypeEnum.HTML);
        assertEquals(1, result.files().size());
        assertEquals(new CodeFile("index.html", "body{margin:0}"), result.files().get(0));
    }

    @Test
    void parse_cssBodyBraces_notMistakenForJson() {
        // body{margin:0} 不是合法 JSON，不能误入 JSON 分支把花括号当 JSON 对象
        String text = "```css\nbody{margin:0}\n```";
        CodeParseResult result = CodeParser.parse(text, CodeGenTypeEnum.HTML);
        assertEquals(new CodeFile("index.html", "body{margin:0}"), result.files().get(0));
    }

    // ==================== 策略③：裸 HTML ====================

    @Test
    void parse_rawHtml_usesWholeTextAsIndexHtml() {
        String html = "<!DOCTYPE html><html><body>raw</body></html>";
        CodeParseResult result = CodeParser.parse(html, CodeGenTypeEnum.HTML);
        assertEquals(List.of(new CodeFile("index.html", html)), result.files());
    }

    // ==================== 异常路径 ====================

    @Test
    void parse_blankInput_throws() {
        assertThrows(CodeParserException.class, () -> CodeParser.parse("   ", CodeGenTypeEnum.HTML));
        assertThrows(CodeParserException.class, () -> CodeParser.parse(null, CodeGenTypeEnum.HTML));
    }

    @Test
    void parse_garbageText_throws() {
        assertThrows(CodeParserException.class,
                () -> CodeParser.parse("对不起，我不明白你在说什么。", CodeGenTypeEnum.HTML));
    }

    @Test
    void parse_jsonWithBlankCode_throws() {
        assertThrows(CodeParserException.class,
                () -> CodeParser.parse("{\"htmlCode\":\"  \",\"description\":\"空\"}", CodeGenTypeEnum.HTML));
    }

    @Test
    void parse_jsonWithoutHtmlCode_throws() {
        assertThrows(CodeParserException.class,
                () -> CodeParser.parse("{\"description\":\"只有描述\"}", CodeGenTypeEnum.HTML));
    }

    // ==================== 辅助方法 ====================

    @Test
    void fileContent_helperFindsByName() {
        CodeParseResult result = CodeParser.parse(
                "{\"htmlCode\":\"h\",\"cssCode\":\"c\",\"jsCode\":\"j\"}", CodeGenTypeEnum.MULTI_FILE);
        assertEquals("h", result.fileContent("index.html"));
        assertEquals("c", result.fileContent("style.css"));
        assertNull(result.fileContent("app.js"));
    }
}
