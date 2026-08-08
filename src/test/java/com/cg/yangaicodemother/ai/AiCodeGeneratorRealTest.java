package com.cg.yangaicodemother.ai;

import com.cg.yangaicodemother.ai.model.HtmlCodeResult;
import com.cg.yangaicodemother.ai.model.MultiFileCodeResult;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实调用 LLM 的集成测试。
 *
 * <p>与 {@link AiCodeGeneratorServiceTest}（mock ChatModel，恒返回 "mock-ai-result"）不同，
 * 本测试加载 {@link ChatModelConfig} 中真实配置的模型（见 application-local.yml 的
 * langchain4j.open-ai.chat-model.*），端到端生成代码并把结果打印到控制台。
 *
 * <p>只加载 ChatModelConfig 这一个配置类，不启动完整 Spring 上下文（不依赖 MySQL），
 * 但需要能联网访问 base-url，且 api-key 有效。
 */
@SpringBootTest(classes = ChatModelConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
class AiCodeGeneratorRealTest {

    @Autowired
    private ChatModel chatModel;

    @Test
    void generateCodeReal() {
        AiCodeGeneratorService service = AiServices.create(AiCodeGeneratorService.class, chatModel);
        HtmlCodeResult result = service.generateCode("做一个简单的博客，不超过20行代码");
        System.out.println("========== generateCode 生成结果 ==========");
        System.out.println("description = " + result.getDescription());
        System.out.println("htmlCode = " + result.getHtmlCode());
        System.out.println("===========================================");
        assertNotNull(result.getHtmlCode());
        assertTrue(result.getHtmlCode().length() > 0, "生成结果不应为空");
    }

    @Test
    void generateMultiCodeReal() {
        AiCodeGeneratorService service = AiServices.create(AiCodeGeneratorService.class, chatModel);
        MultiFileCodeResult result = service.generateMultiCode("做一个简单的博客，不超过50行代码");
        System.out.println("========== generateMultiCode 生成结果 ==========");
        System.out.println("description = " + result.getDescription());
        System.out.println("htmlCode = " + result.getHtmlCode());
        System.out.println("cssCode = " + result.getCssCode());
        System.out.println("jsCode = " + result.getJsCode());
        System.out.println("===============================================");
        assertNotNull(result.getHtmlCode());
        assertTrue(result.getHtmlCode().length() > 0, "生成结果不应为空");
    }
}
