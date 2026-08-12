package com.cg.yangaicodemother.service;

import cn.hutool.core.io.FileUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VueImportRepairer 单元测试：模型生成的工程偶发「引用了不存在的组件/模块」，
 * 修复器应补齐占位文件，让后续 vite build 不因 ENOENT 失败。
 */
class VueImportRepairerTest {

    @TempDir
    File root;

    @Test
    void repair_createsMissingVueComponent() {
        // 先补一个存在的 Home.vue，让 router 只缺 About.vue，验证「只补缺失、不重复补已有」
        File pagesDir = FileUtil.mkdir(root.getAbsolutePath() + "/src/pages");
        FileUtil.writeUtf8String("<template><div>home</div></template>", new File(pagesDir, "Home.vue"));
        File routerDir = FileUtil.mkdir(root.getAbsolutePath() + "/src/router");
        FileUtil.writeUtf8String(
                "import Home from '../pages/Home.vue'\n"
                        + "const About = () => import('../pages/About.vue')\n"
                        + "export default [{ path: '/', component: Home }, { path: '/about', component: About }]\n",
                new File(routerDir, "index.js"));

        int created = VueImportRepairer.repair(root);

        assertEquals(1, created, "只补齐缺失的 About.vue");
        File about = FileUtil.file(root, "src/pages/About.vue");
        assertTrue(FileUtil.exist(about), "应补齐 src/pages/About.vue");
        assertTrue(FileUtil.readUtf8String(about).contains("<template>"), "占位组件应含 template");
    }

    @Test
    void repair_skipsExistingTarget() {
        // src/App.vue 与 src/pages/Home.vue 同层引用（./pages/Home.vue → src/pages/Home.vue），已存在则不补齐
        File pagesDir = FileUtil.mkdir(root.getAbsolutePath() + "/src/pages");
        FileUtil.writeUtf8String("<template><div>ok</div></template>", new File(pagesDir, "Home.vue"));
        FileUtil.writeUtf8String(
                "import Home from './pages/Home.vue'\n",
                new File(root, "src/App.vue"));

        int created = VueImportRepairer.repair(root);

        assertEquals(0, created, "已存在的引用不重复补齐");
        assertTrue(FileUtil.readUtf8String(FileUtil.file(root, "src/pages/Home.vue")).contains("ok"));
    }

    @Test
    void repair_namedImportsMissingJsModule() {
        FileUtil.writeUtf8String(
                "import { formatDate, greet } from './utils'\n",
                new File(root, "src/main.js"));

        int created = VueImportRepairer.repair(root);

        assertEquals(1, created);
        String stub = FileUtil.readUtf8String(FileUtil.file(root, "src/utils.js"));
        assertTrue(stub.contains("export const formatDate = noop;"), "命名导出应为安全 noop");
        assertTrue(stub.contains("export const greet = noop;"));
        assertTrue(stub.contains("export default noop;"));
        assertTrue(stub.contains("new Proxy"), "应使用永不抛错的 Proxy noop");
        assertTrue(!stub.contains("= undefined"), "不应导出 undefined 导致运行期崩溃");
    }

    @Test
    void repair_missingVueComponentIsVisiblePlaceholder() {
        FileUtil.writeUtf8String(
                "import About from './pages/About.vue'\n",
                new File(root, "src/main.js"));

        int created = VueImportRepairer.repair(root);

        assertEquals(1, created);
        String stub = FileUtil.readUtf8String(FileUtil.file(root, "src/pages/About.vue"));
        assertTrue(stub.contains("待完善模块"), "占位组件应显示可见文案而非空白");
        assertTrue(stub.contains("<template>"), "占位组件应含模板");
        assertTrue(stub.contains("自动补齐"), "应保留自动补齐标记");
    }

    @Test
    void repair_skipsNodeModulesAndBareImports() {
        FileUtil.mkdir(root.getAbsolutePath() + "/node_modules/vue/dist");
        FileUtil.writeUtf8String(
                "import { createApp } from 'vue'\nimport Router from 'vue-router'\n",
                new File(root, "src/main.js"));
        FileUtil.writeUtf8String(
                "import x from 'vue'\n",
                new File(root, "node_modules/vue/dist/index.js"));

        int created = VueImportRepairer.repair(root);

        assertEquals(0, created, "裸模块导入与 node_modules 下的文件都不应触发补齐");
    }

    @Test
    void repair_aliasAtPointsToSrc() {
        FileUtil.writeUtf8String(
                "import Card from '@/components/Card.vue'\n",
                new File(root, "src/App.vue"));

        int created = VueImportRepairer.repair(root);

        assertEquals(1, created);
        assertTrue(FileUtil.exist(FileUtil.file(root, "src/components/Card.vue")),
                "@/ 别名应解析到 src/ 下补齐");
    }

    @Test
    void repair_noStubWhenDirectoryIndexExists() {
        // 模型写 import router from './router' + 真实 src/router/index.js:引用本身有效(vite 解析到目录 index),
        // 修复器不得补 src/router.js 桩 —— 否则桩遮蔽真实路由,app.use({}) 导致部署预览空白
        FileUtil.mkdir(root.getAbsolutePath() + "/src");
        FileUtil.writeUtf8String(
                "import router from './router'\nexport default router\n",
                new File(root, "src/main.js"));
        FileUtil.writeUtf8String(
                "export default { install() {} }\n",
                new File(root, "src/router/index.js"));

        int created = VueImportRepairer.repair(root);

        assertEquals(0, created, "引用可解析到目录 index,不应补任何桩");
        org.junit.jupiter.api.Assertions.assertFalse(
                FileUtil.exist(FileUtil.file(root, "src/router.js")),
                "不得生成 router.js 遮蔽 router/index.js");
    }

    @Test
    void repair_noStubWhenExtensionResolves() {
        // import './pages/Home' 而 pages/Home.vue 存在:vite 经 .vue 扩展名解析,不补 Home.js
        FileUtil.mkdir(root.getAbsolutePath() + "/src");
        FileUtil.writeUtf8String(
                "import Home from './pages/Home'\nexport default Home\n",
                new File(root, "src/main.js"));
        FileUtil.writeUtf8String(
                "<template><h1>Home</h1></template>\n",
                new File(root, "src/pages/Home.vue"));

        int created = VueImportRepairer.repair(root);

        assertEquals(0, created, "Home.vue 已存在,不应补桩");
        org.junit.jupiter.api.Assertions.assertFalse(
                FileUtil.exist(FileUtil.file(root, "src/pages/Home.js")),
                "不得补 Home.js 遮蔽 Home.vue");
    }

    @Test
    void repair_aliasDirectoryIndexNoStub() {
        // import { rows } from '@/utils' 而 utils/index.js 存在:别名 @/ → src/,目录 index 可解析,不补 utils.js
        FileUtil.mkdir(root.getAbsolutePath() + "/src");
        FileUtil.writeUtf8String(
                "<script setup>\nimport { rows } from '@/utils'\nconsole.log(rows)\n</script>\n",
                new File(root, "src/App.vue"));
        FileUtil.writeUtf8String(
                "export const rows = []\n",
                new File(root, "src/utils/index.js"));

        int created = VueImportRepairer.repair(root);

        assertEquals(0, created, "别名目录经 index 可解析,不应补桩");
        org.junit.jupiter.api.Assertions.assertFalse(
                FileUtil.exist(FileUtil.file(root, "src/utils.js")),
                "不得补 utils.js 遮蔽 utils/index.js");
    }
}
