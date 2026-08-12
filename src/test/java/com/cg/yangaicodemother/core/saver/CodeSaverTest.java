package com.cg.yangaicodemother.core.saver;

import cn.hutool.core.io.FileUtil;
import com.cg.yangaicodemother.ai.model.HtmlCodeResult;
import com.cg.yangaicodemother.ai.model.MultiFileCodeResult;
import com.cg.yangaicodemother.core.parser.CodeFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CodeSaver 单元测试（真实写盘）。
 *
 * <p>CodeSaver 的保存根目录是写死的（user.dir/tmp/code_output），无法注入，
 * 因此这里做真实写盘验证，用例结束后把本次生成的目录清理掉。
 */
class CodeSaverTest {

    /** 记录本次测试创建过的目录，@AfterEach 统一清理，避免污染产物目录 */
    private final List<File> createdDirs = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (File dir : createdDirs) {
            FileUtil.del(dir);
        }
    }

    @Test
    void saveHtml_shouldCreateIndexHtmlUnderAppIdDir() {
        HtmlCodeResult result = new HtmlCodeResult();
        result.setHtmlCode("<html>hello</html>");

        CodeSaveResult saved = CodeSaver.saveHtml(result, 1001L);
        createdDirs.add(saved.dir());

        File dir = saved.dir();
        assertTrue(dir.isDirectory(), "应创建保存目录");
        assertEquals("html_1001", dir.getName(), "目录名应为 {bizType}_{appId}");
        assertTrue(dir.getPath().contains("code_output"), "目录应在 code_output 根目录下");
        File index = new File(dir, "index.html");
        assertTrue(index.isFile(), "应生成 index.html");
        assertEquals("<html>hello</html>", FileUtil.readString(index, StandardCharsets.UTF_8));
        assertEquals(List.of("index.html"), saved.fileNames());
    }

    @Test
    void saveMultiFile_shouldCreateThreeFiles() {
        MultiFileCodeResult result = new MultiFileCodeResult();
        result.setHtmlCode("<html>");
        result.setCssCode("body{}");
        result.setJsCode("console.log(1)");

        CodeSaveResult saved = CodeSaver.saveMultiFile(result, 2002L);
        createdDirs.add(saved.dir());

        File dir = saved.dir();
        assertTrue(dir.isDirectory(), "应创建保存目录");
        assertEquals("multi_file_2002", dir.getName(), "目录名应为 {bizType}_{appId}");
        assertEquals("<html>", FileUtil.readString(new File(dir, "index.html"), StandardCharsets.UTF_8));
        assertEquals("body{}", FileUtil.readString(new File(dir, "style.css"), StandardCharsets.UTF_8));
        assertEquals("console.log(1)", FileUtil.readString(new File(dir, "script.js"), StandardCharsets.UTF_8));
        assertEquals(List.of("index.html", "style.css", "script.js"), saved.fileNames());
    }

    @Test
    void saveFiles_sameAppId_shouldReuseSameDir() {
        List<CodeFile> files = List.of(new CodeFile("index.html", "a"));

        CodeSaveResult dir1 = CodeSaver.saveFiles(files, "html", 3003L);
        CodeSaveResult dir2 = CodeSaver.saveFiles(files, "html", 3003L);
        createdDirs.add(dir1.dir());

        assertEquals(dir1.saveDir(), dir2.saveDir(), "同一应用再次保存应落到同一目录");
        assertTrue(dir1.dir().isDirectory());
        assertTrue(dir2.dir().isDirectory());
    }

    @Test
    void saveFiles_differentAppId_shouldCreateDifferentDirs() {
        List<CodeFile> files = List.of(new CodeFile("index.html", "a"));

        CodeSaveResult dir1 = CodeSaver.saveFiles(files, "html", 4004L);
        CodeSaveResult dir2 = CodeSaver.saveFiles(files, "html", 4005L);
        createdDirs.add(dir1.dir());
        createdDirs.add(dir2.dir());

        assertNotEquals(dir1.saveDir(), dir2.saveDir(), "不同应用应保存到不同目录");
        assertTrue(dir1.dir().isDirectory());
        assertTrue(dir2.dir().isDirectory());
    }

    @Test
    void saveFiles_nullAppId_shouldThrow() {
        List<CodeFile> files = List.of(new CodeFile("index.html", "a"));
        assertThrows(CodeSaveException.class, () -> CodeSaver.saveFiles(files, "html", null));
    }

    @Test
    void saveFiles_emptyFiles_shouldThrow() {
        assertThrows(CodeSaveException.class, () -> CodeSaver.saveFiles(List.of(), "html", 1L));
    }

    // ==================== buildCurrentCodeContext（对话即改代码的小幅度修改基线） ====================

    @Test
    void buildCurrentCodeContext_noCodeDir_shouldReturnEmpty() {
        assertEquals("", CodeSaver.buildCurrentCodeContext("html", 5001L), "未生成的目录应返回空串");
    }

    @Test
    void buildCurrentCodeContext_shouldIncludeCodeWithGuide() {
        List<CodeFile> files = List.of(
                new CodeFile("index.html", "<h1>hello</h1>"),
                new CodeFile("style.css", "h1{color:red}"),
                new CodeFile("script.js", "console.log(1)"));
        CodeSaveResult saved = CodeSaver.saveFiles(files, "html", 5002L);
        createdDirs.add(saved.dir());

        String ctx = CodeSaver.buildCurrentCodeContext("html", 5002L);

        assertTrue(ctx.contains("小幅度修改"), "应包含小幅度修改引导语");
        assertTrue(ctx.contains("===== index.html"), "应包含 index.html 文件头");
        assertTrue(ctx.contains("<h1>hello</h1>"), "应包含代码内容");
        assertTrue(ctx.contains("console.log(1)"), "应包含 script.js 内容");
    }

    @Test
    void buildCurrentCodeContext_shouldSkipNodeModulesAndNonText() {
        List<CodeFile> files = List.of(new CodeFile("index.html", "<html>"));
        CodeSaveResult saved = CodeSaver.saveFiles(files, "html", 5003L);
        createdDirs.add(saved.dir());
        File dir = saved.dir();
        // 模拟构建产物目录 + 二进制文件，都不应进入上下文
        FileUtil.mkdir(FileUtil.file(dir, "node_modules", "dep"));
        FileUtil.writeString("var x=1;", FileUtil.file(dir, "node_modules", "dep", "a.js"), StandardCharsets.UTF_8);
        FileUtil.writeString("PNG", FileUtil.file(dir, "logo.png"), StandardCharsets.UTF_8);

        String ctx = CodeSaver.buildCurrentCodeContext("html", 5003L);

        assertTrue(ctx.contains("===== index.html"), "应包含 index.html");
        assertTrue(!ctx.contains("node_modules"), "应跳过 node_modules 目录");
        assertTrue(!ctx.contains("logo.png"), "应跳过非文本扩展名文件");
    }

    @Test
    void buildCurrentCodeContext_overLimit_shouldTruncate() {
        List<CodeFile> files = List.of(new CodeFile("index.html", "a"));
        CodeSaveResult saved = CodeSaver.saveFiles(files, "html", 5004L);
        createdDirs.add(saved.dir());
        // 写入远超上限的大文件，应触发截断提示而非整段塞进 prompt
        FileUtil.writeString("x".repeat(30000), new File(saved.dir(), "big.js"), StandardCharsets.UTF_8);

        String ctx = CodeSaver.buildCurrentCodeContext("html", 5004L);

        assertTrue(ctx.contains("已截断") || ctx.contains("过长"), "超出上限应截断并提示");
    }
}
