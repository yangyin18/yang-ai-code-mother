package com.cg.yangaicodemother.core;

import com.cg.yangaicodemother.ai.AiCodeGeneratorServiceFactory;
import com.cg.yangaicodemother.ai.ChatModelConfig;
import com.cg.yangaicodemother.service.AppService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CodeGenFacade 真实调用 LLM 的端到端集成测试。
 *
 * <p>加载 ChatModelConfig（真实 DeepSeek 模型）、AiCodeGeneratorServiceFactory、CodeGenFacade
 * 三个配置/Bean，走完「生成 → 校验 → 落盘」全流程。需要联网且 api-key 有效。
 */
@SpringBootTest(
        classes = {ChatModelConfig.class, AiCodeGeneratorServiceFactory.class, CodeGenFacade.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
class CodeGenFacadeRealTest {

    @Autowired
    private CodeGenFacade codeGenFacade;

    /** 门面依赖 AppService，但本测试不校验应用模式，用 mock 占位以满足依赖注入 */
    @MockitoBean
    private AppService appService;

    @Test
    void generateHtml() {
        CodeGenResult result = codeGenFacade.generate("做一个简单的博客，不超过20行代码", "html", 1L);
        System.out.println("========== 门面 generateHtml 结果 ==========");
        System.out.println("type        = " + result.getCodeGenType());
        System.out.println("description = " + result.getDescription());
        System.out.println("saveDir     = " + result.getSaveDir());
        System.out.println("fileNames   = " + result.getFileNames());
        System.out.println("htmlCode    = " + result.getHtmlCode());
        System.out.println("============================================");

        assertNotNull(result.getHtmlCode());
        assertTrue(result.getHtmlCode().length() > 0, "HTML 代码不应为空");
        File dir = new File(result.getSaveDir());
        assertTrue(dir.isDirectory(), "保存目录应存在");
        assertTrue(new File(dir, "index.html").isFile(), "应生成 index.html");
    }

    @Test
    void generateMultiFile() {
        CodeGenResult result = codeGenFacade.generate("做一个简单的博客，不超过50行代码", "multi_file", 1L);
        System.out.println("========== 门面 generateMultiFile 结果 ==========");
        System.out.println("type        = " + result.getCodeGenType());
        System.out.println("description = " + result.getDescription());
        System.out.println("saveDir     = " + result.getSaveDir());
        System.out.println("fileNames   = " + result.getFileNames());
        System.out.println("htmlCode    = " + result.getHtmlCode());
        System.out.println("cssCode     = " + result.getCssCode());
        System.out.println("jsCode      = " + result.getJsCode());
        System.out.println("=================================================");

        assertNotNull(result.getHtmlCode());
        assertTrue(result.getHtmlCode().length() > 0, "HTML 代码不应为空");
        assertNotNull(result.getCssCode());
        assertNotNull(result.getJsCode());
        File dir = new File(result.getSaveDir());
        assertTrue(dir.isDirectory(), "保存目录应存在");
        assertTrue(new File(dir, "index.html").isFile(), "应生成 index.html");
        assertTrue(new File(dir, "style.css").isFile(), "应生成 style.css");
        assertTrue(new File(dir, "script.js").isFile(), "应生成 script.js");
    }
}
