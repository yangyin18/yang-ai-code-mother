package com.cg.yangaicodemother.service;

import cn.hutool.core.io.FileUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VueProjectScaffolder 单元测试：生成完成后用固定模板兜底缺失的关键文件，
 * 对合法的模型文件不覆盖，并确定性清理 router.js 双写法。
 */
class VueProjectScaffolderTest {

    @TempDir
    File root;

    private static final String[] BASE_FILES = {
            "package.json", "vite.config.js", "index.html",
            "src/main.js", "src/App.vue", "src/router/index.js"
    };

    @Test
    void scaffold_createsAllBaseFilesInEmptyDir() {
        List<String> written = VueProjectScaffolder.scaffold(root);

        assertEquals(6, written.size(), "空目录应补写全部 6 个关键文件");
        for (String p : BASE_FILES) {
            assertTrue(FileUtil.exist(FileUtil.file(root, p)), p + " 应被补写");
        }
        String pkg = FileUtil.readUtf8String(FileUtil.file(root, "package.json"));
        assertTrue(pkg.contains("\"build\": \"vite build\""), "黄金模板应含 build 脚本");
        assertTrue(pkg.contains("\"vue\": \"^3.3.4\""), "黄金模板应含固定 vue 版本");
        String main = FileUtil.readUtf8String(FileUtil.file(root, "src/main.js"));
        assertTrue(main.contains("from './router/index'"), "main.js 应显式引用 router/index");
        String router = FileUtil.readUtf8String(FileUtil.file(root, "src/router/index.js"));
        assertTrue(router.contains("createWebHashHistory"), "黄金 router 应使用 hash 模式");
    }

    @Test
    void scaffold_preservesValidPackageJsonAndFixesScripts() {
        FileUtil.writeUtf8String(
                "{\"name\":\"my-app\",\"dependencies\":{\"axios\":\"^1.6.0\",\"vue\":\"^2.7.16\"},\"devDependencies\":{}}",
                new File(root, "package.json"));

        VueProjectScaffolder.scaffold(root);

        String pkg = FileUtil.readUtf8String(FileUtil.file(root, "package.json"));
        assertTrue(pkg.contains("\"axios\""), "应保留模型声明的额外依赖 axios");
        assertTrue(pkg.contains("^1.6.0"), "应保留 axios 的版本号");
        assertTrue(pkg.contains("\"build\""), "应补 build 脚本");
        assertTrue(pkg.contains("vite build"), "build 脚本值应为 vite build");
        assertTrue(pkg.contains("\"vue\"") && pkg.contains("^3.3.4"),
                "vue major 不符(^2)应覆盖为固定 ^3.3.4");
        assertFalse(pkg.contains("^2.7.16"), "不应再残留旧的 vue ^2 版本");
        assertTrue(pkg.contains("\"vite\"") && pkg.contains("^4.4.5"), "应补 vite 依赖");
        assertTrue(pkg.contains("\"@vitejs/plugin-vue\""), "应补 @vitejs/plugin-vue 依赖");
    }

    @Test
    void scaffold_replacesInvalidPackageJson() {
        FileUtil.writeUtf8String("not json{{{", new File(root, "package.json"));

        VueProjectScaffolder.scaffold(root);

        String pkg = FileUtil.readUtf8String(FileUtil.file(root, "package.json"));
        assertTrue(pkg.contains("\"scripts\""), "非法 package.json 应被黄金模板替换");
        assertTrue(pkg.contains("\"vite\""), "黄金模板应包含 vite 依赖");
    }

    @Test
    void scaffold_doesNotOverwriteExistingFiles() {
        FileUtil.mkdir(root.getAbsolutePath() + "/src/router");
        FileUtil.writeUtf8String("body{margin:0}", new File(root, "index.html"));
        FileUtil.writeUtf8String("// 模型写的 router\nimport x from 'vue-router'\n",
                new File(root, "src/router/index.js"));

        VueProjectScaffolder.scaffold(root);

        assertEquals("body{margin:0}", FileUtil.readUtf8String(FileUtil.file(root, "index.html")),
                "已存在的 index.html 不应被覆盖");
        assertTrue(FileUtil.readUtf8String(FileUtil.file(root, "src/router/index.js")).contains("模型写的 router"),
                "已存在的 router 不应被覆盖");
        // 模型已写 index.html 与 router,本次只补其余 4 个缺失文件
        assertTrue(FileUtil.exist(FileUtil.file(root, "package.json")), "缺失的 package.json 仍应补齐");
        assertTrue(FileUtil.exist(FileUtil.file(root, "src/App.vue")), "缺失的 App.vue 仍应补齐");
        assertTrue(FileUtil.exist(FileUtil.file(root, "src/main.js")), "缺失的 main.js 仍应补齐");
        assertTrue(FileUtil.exist(FileUtil.file(root, "vite.config.js")), "缺失的 vite.config.js 仍应补齐");
    }

    @Test
    void scaffold_removesRouterConflict() {
        FileUtil.mkdir(root.getAbsolutePath() + "/src/router");
        FileUtil.writeUtf8String("export default {}\n", new File(root, "src/router/index.js"));
        FileUtil.writeUtf8String("export default {}\n", new File(root, "src/router.js"));

        VueProjectScaffolder.scaffold(root);

        assertFalse(FileUtil.exist(FileUtil.file(root, "src/router.js")), "router.js 与 index 并存时应删除 router.js");
        assertTrue(FileUtil.exist(FileUtil.file(root, "src/router/index.js")), "应保留 router/index.js");
    }

    @Test
    void scaffold_isIdempotent() {
        VueProjectScaffolder.scaffold(root);
        List<String> second = VueProjectScaffolder.scaffold(root);

        assertEquals(0, second.size(), "二次加固不应再补写任何文件");
        assertTrue(FileUtil.exist(FileUtil.file(root, "src/main.js")));
    }
}
