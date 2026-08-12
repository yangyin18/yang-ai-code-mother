package com.cg.yangaicodemother.service;

import cn.hutool.core.io.FileUtil;
import com.cg.yangaicodemother.ai.model.HtmlCodeResult;
import com.cg.yangaicodemother.core.saver.CodeSaveResult;
import com.cg.yangaicodemother.core.saver.CodeSaver;
import com.cg.yangaicodemother.exception.BusinessException;
import com.cg.yangaicodemother.exception.ErrorCode;
import com.cg.yangaicodemother.model.entity.App;
import com.cg.yangaicodemother.model.vo.DeployResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DeployService 单元测试（真实文件系统，用临时目录）。
 *
 * <p>DeployService 的 source-root / web-root / base-url 通过构造器注入，测试里用临时目录实例化，
 * 用例结束后统一清理，避免污染真实的 nginx 站点目录。
 */
class DeployServiceTest {

    private static final String BASE_URL = "http://test/apps";

    /** 生成代码源目录（模拟 CodeSaver 输出） */
    private Path sourceRoot;

    /** nginx 站点根目录（模拟部署目标） */
    private Path webRoot;

    private DeployService service;

    @BeforeEach
    void setUp() throws IOException {
        sourceRoot = Files.createTempDirectory("deploy-source");
        webRoot = Files.createTempDirectory("deploy-web");
        service = new DeployService(sourceRoot.toString(), webRoot.toString(), BASE_URL);
    }

    @AfterEach
    void tearDown() {
        FileUtil.del(sourceRoot.toFile());
        FileUtil.del(webRoot.toFile());
    }

    @Test
    void deployFirstTime_shouldPublishFilesAndReturnUrl() throws IOException {
        Path src = sourceRoot.resolve("html_1001");
        Files.createDirectories(src);
        Files.writeString(src.resolve("index.html"), "<html>ok</html>", StandardCharsets.UTF_8);
        App app = App.builder().id(1001L).codeGenType("html").build();

        DeployResult result = service.deploy(app);

        assertNotNull(result.deployKey(), "首次部署应生成 deployKey");
        File deployed = new File(webRoot.toFile(), result.deployKey() + "/index.html");
        assertTrue(deployed.isFile(), "部署文件应出现在 {web-root}/{deployKey}/ 下");
        assertEquals("<html>ok</html>", FileUtil.readString(deployed, StandardCharsets.UTF_8));
        assertEquals(BASE_URL + "/" + result.deployKey() + "/", result.deployUrl());
        assertNotNull(result.deployedTime());
    }

    @Test
    void redeploy_shouldReuseKeyAndOverwrite() throws IOException {
        Path src = sourceRoot.resolve("html_2002");
        Files.createDirectories(src);
        Files.writeString(src.resolve("index.html"), "v1", StandardCharsets.UTF_8);
        Files.writeString(src.resolve("old.js"), "old", StandardCharsets.UTF_8);
        App app = App.builder().id(2002L).codeGenType("html").deployKey("abc12345").build();

        DeployResult first = service.deploy(app);
        // 改源：更新 index.html、删除 old.js，再部署
        Files.writeString(src.resolve("index.html"), "v2", StandardCharsets.UTF_8);
        Files.delete(src.resolve("old.js"));
        DeployResult second = service.deploy(app);

        assertEquals(first.deployKey(), second.deployKey(), "重复部署应复用 deployKey");
        assertEquals(first.deployUrl(), second.deployUrl(), "访问地址应保持稳定");
        File deployed = new File(webRoot.toFile(), "abc12345/index.html");
        assertEquals("v2", FileUtil.readString(deployed, StandardCharsets.UTF_8), "内容应覆盖为最新");
        assertFalse(new File(webRoot.toFile(), "abc12345/old.js").exists(), "上一次的残留文件应被清除");
    }

    @Test
    void deploy_noSourceDir_shouldThrow() {
        App app = App.builder().id(3003L).codeGenType("html").build();
        BusinessException ex = assertThrows(BusinessException.class, () -> service.deploy(app));
        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), ex.getCode());
    }

    @Test
    void deploy_blankCodeGenType_shouldThrow() {
        App app = App.builder().id(4004L).build();
        BusinessException ex = assertThrows(BusinessException.class, () -> service.deploy(app));
        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), ex.getCode());
    }

    @Test
    void integration_saveThenDeploy_shouldPublish() {
        HtmlCodeResult result = new HtmlCodeResult();
        result.setHtmlCode("<html>deploy</html>");

        CodeSaveResult saved = CodeSaver.saveHtml(result, 8888L);
        try {
            // 用与 CodeSaver.FILE_SAVE_ROOT_DIR 相同的默认源目录，验证「保存 → 部署」闭环
            DeployService svc = new DeployService(
                    System.getProperty("user.dir") + "/tmp/code_output",
                    webRoot.toString(), BASE_URL);
            App app = App.builder().id(8888L).codeGenType("html").build();

            DeployResult dr = svc.deploy(app);

            File deployed = new File(webRoot.toFile(), dr.deployKey() + "/index.html");
            assertTrue(deployed.isFile(), "CodeSaver 落盘的文件应被发布到站点根目录");
            assertEquals("<html>deploy</html>", FileUtil.readString(deployed, StandardCharsets.UTF_8));
        } finally {
            FileUtil.del(saved.dir());
        }
    }
}
