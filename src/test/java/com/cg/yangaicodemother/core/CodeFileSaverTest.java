package com.cg.yangaicodemother.core;

import cn.hutool.core.io.FileUtil;
import com.cg.yangaicodemother.ai.model.HtmlCodeResult;
import com.cg.yangaicodemother.ai.model.MultiFileCodeResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CodeFileSaver 单元测试。
 *
 * <p>CodeFileSaver 的保存根目录是写死的（user.dir/tmp/code_output），无法注入，
 * 因此这里做真实写盘验证，用例结束后把本次生成的目录清理掉。
 */
class CodeFileSaverTest {

    /** 记录本次测试创建过的目录，@AfterEach 统一清理，避免污染产物目录 */
    private final List<File> createdDirs = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (File dir : createdDirs) {
            FileUtil.del(dir);
        }
    }

    @Test
    void saveHtmlCodeResult_shouldCreateIndexHtmlWithContent() {
        HtmlCodeResult result = new HtmlCodeResult();
        result.setHtmlCode("<html>hello</html>");

        File dir = CodeFileSaver.saveHtmlCodeResult(result);
        createdDirs.add(dir);

        assertTrue(dir.isDirectory(), "应创建保存目录");
        assertTrue(dir.getPath().contains("code_output"), "目录应在 code_output 根目录下");
        File index = new File(dir, "index.html");
        assertTrue(index.isFile(), "应生成 index.html");
        assertEquals("<html>hello</html>", FileUtil.readString(index, StandardCharsets.UTF_8));
    }

    @Test
    void saveMultiFileCodeResult_shouldCreateThreeFiles() {
        MultiFileCodeResult result = new MultiFileCodeResult();
        result.setHtmlCode("<html>");
        result.setCssCode("body{}");
        result.setJsCode("console.log(1)");

        File dir = CodeFileSaver.saveMultiFileCodeResult(result);
        createdDirs.add(dir);

        assertTrue(dir.isDirectory(), "应创建保存目录");
        assertEquals("<html>", FileUtil.readString(new File(dir, "index.html"), StandardCharsets.UTF_8));
        assertEquals("body{}", FileUtil.readString(new File(dir, "style.css"), StandardCharsets.UTF_8));
        assertEquals("console.log(1)", FileUtil.readString(new File(dir, "script.js"), StandardCharsets.UTF_8));
    }

    @Test
    void saveHtmlCodeResult_twice_shouldGenerateUniqueDirs() {
        HtmlCodeResult result = new HtmlCodeResult();
        result.setHtmlCode("a");

        File dir1 = CodeFileSaver.saveHtmlCodeResult(result);
        File dir2 = CodeFileSaver.saveHtmlCodeResult(result);
        createdDirs.add(dir1);
        createdDirs.add(dir2);

        assertNotEquals(dir1.getAbsolutePath(), dir2.getAbsolutePath(), "两次保存的目录应互不相同");
        assertTrue(dir1.isDirectory());
        assertTrue(dir2.isDirectory());
    }
}
