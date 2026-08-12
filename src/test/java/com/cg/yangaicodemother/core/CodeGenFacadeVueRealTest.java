package com.cg.yangaicodemother.core;

import com.cg.yangaicodemother.ai.AiCodeGeneratorServiceFactory;
import com.cg.yangaicodemother.ai.ChatModelConfig;
import com.cg.yangaicodemother.ai.memory.RedisChatMemoryStore;
import com.cg.yangaicodemother.service.AppService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vue 项目生成端到端真实测试。
 *
 * <p>真调 LLM 生成一个小 Vue 项目（DeepSeek，需联网 + api-key），验证生成期黄金兜底后
 * 6 个关键文件齐全，并真实执行 {@code npm install && npm run build} 产出 dist，
 * 端到端确认「生成的 Vue 项目能运行」。依赖本机 node/npm（与部署环境一致）。
 */
@SpringBootTest(
        classes = {ChatModelConfig.class, AiCodeGeneratorServiceFactory.class, CodeGenFacade.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
class CodeGenFacadeVueRealTest {

    @Autowired
    private CodeGenFacade codeGenFacade;

    /** 门面依赖 AppService，本测试用基础模式（显式 codeGenType），用 mock 占位满足依赖注入 */
    @MockitoBean
    private AppService appService;

    /** 工厂会创建 AiChatService（依赖 RedisChatMemoryStore），本测试不涉及对话，用 mock 占位 */
    @MockitoBean
    private RedisChatMemoryStore redisChatMemoryStore;

    private static final String[] REQUIRED_FILES = {
            "package.json", "vite.config.js", "index.html",
            "src/main.js", "src/App.vue", "src/router/index.js"
    };

    @Test
    void generateVueProject_buildsDist() throws Exception {
        CodeGenResult result = codeGenFacade.generate(
                "做一个简单的产品展示官网，含首页、关于我们两个页面", "vue", 1L);
        System.out.println("========== 门面 generateVue 结果 ==========");
        System.out.println("saveDir   = " + result.getSaveDir());
        System.out.println("fileNames = " + result.getFileNames());
        System.out.println("============================================");

        File dir = new File(result.getSaveDir());
        for (String required : REQUIRED_FILES) {
            assertTrue(new File(dir, required).isFile(),
                    "生成期加固后应存在关键文件：" + required);
        }

        // 端到端可运行验证：真实 npm install && npm run build
        runNpm(dir, 300, "install");
        runNpm(dir, 180, "run", "build");
        assertTrue(new File(dir, "dist/index.html").isFile(),
                "npm run build 应产出 dist/index.html");
    }

    /** Windows 下经 cmd 执行 npm 子命令，超时/非零退出码抛异常并附输出尾部 */
    private void runNpm(File workDir, int timeoutSeconds, String... args) throws Exception {
        ProcessBuilder pb = new ProcessBuilder();
        pb.command("cmd.exe", "/c", "npm");
        for (String a : args) {
            pb.command().add(a);
        }
        pb.directory(workDir);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("npm " + String.join(" ", args) + " 超时");
        }
        if (process.exitValue() != 0) {
            String[] lines = output.split("\n");
            int from = Math.max(0, lines.length - 20);
            String tail = String.join("\n", Arrays.copyOfRange(lines, from, lines.length));
            throw new IllegalStateException("npm " + String.join(" ", args)
                    + " 失败（exit=" + process.exitValue() + "）：\n" + tail);
        }
    }
}
