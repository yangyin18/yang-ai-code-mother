package com.cg.yangaicodemother.service;

import cn.hutool.core.io.FileUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Vue 工程「黄金兜底」：大模型生成的工程偶发缺失关键文件（package.json、vite.config.js、
 * index.html、src/main.js、src/App.vue、src/router/index.js），或 package.json 非法/缺脚本/缺核心依赖。
 * 这类文件缺失会让 {@code npm run build} 直接失败。本类在生成完成后用固定模板确定性补齐。
 *
 * <p>原则：
 * <ul>
 *   <li><b>只补缺失，不覆盖模型已写的合法文件</b>——避免二次破坏模型写好的 router/页面；</li>
 *   <li>package.json 缺/非法 → 整体写黄金模板；合法 → 深度合并：<b>保留模型声明的全部其它依赖</b>，
 *       只强制 scripts（dev/build/preview）与核心依赖（vue/vue-router/vite/@vitejs/plugin-vue）存在，
 *       版本 major 不符时覆盖为固定版本；</li>
 *   <li>确定性清理 <code>src/router.js</code> 与 <code>src/router/index.js</code> 并存的「双写法」——
 *       避免桩遮蔽真实路由导致空白页。</li>
 * </ul>
 *
 * <p>幂等：已存在的正确文件不覆盖，可重复调用。
 */
@Slf4j
public final class VueProjectScaffolder {

    private VueProjectScaffolder() {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 黄金 package.json 模板（缺/非法时的整文件兜底） */
    private static final String GOLDEN_PACKAGE_JSON = """
            {
              "name": "generated-vue-app",
              "version": "1.0.0",
              "private": true,
              "type": "module",
              "scripts": {
                "dev": "vite",
                "build": "vite build",
                "preview": "vite preview"
              },
              "dependencies": {
                "vue": "^3.3.4",
                "vue-router": "^4.2.4"
              },
              "devDependencies": {
                "@vitejs/plugin-vue": "^4.2.3",
                "vite": "^4.4.5"
              }
            }
            """;

    /** 黄金 vite.config.js（与提示词固定参考配置保持一致）
     *  <p>resolve.alias.vue 指向带编译器的完整构建：Vite 默认按 runtime-only 解析 vue，
     *  无法编译模型/脚手架常用的内联 template 字符串组件，会导致页面空白。这里固定为完整构建。 */
    private static final String GOLDEN_VITE_CONFIG = """
            import { defineConfig } from 'vite'
            import vue from '@vitejs/plugin-vue'
            import { fileURLToPath, URL } from 'node:url'

            export default defineConfig({
              base: './',
              plugins: [vue()],
              resolve: {
                alias: {
                  '@': fileURLToPath(new URL('./src', import.meta.url)),
                  vue: 'vue/dist/vue.esm-bundler.js'
                }
              }
            })
            """;

    /** 黄金 index.html */
    private static final String GOLDEN_INDEX_HTML = """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
              <meta charset="UTF-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1.0" />
              <title>生成的应用</title>
            </head>
            <body>
              <div id="app"></div>
              <script type="module" src="/src/main.js"></script>
            </body>
            </html>
            """;

    /** 黄金 src/main.js：只引用由本类保证存在的 App.vue 与 router/index.js，保证自洽 */
    private static final String GOLDEN_MAIN_JS = """
            import { createApp } from 'vue'
            import App from './App.vue'
            import router from './router/index'

            createApp(App).use(router).mount('#app')
            """;

    /** 黄金 src/App.vue：根壳组件，只放 router-view，不引入其它文件 */
    private static final String GOLDEN_APP_VUE = """
            <template>
              <router-view />
            </template>
            """;

    /** 黄金 src/router/index.js：最小 hash 路由，含一个内联占位首页，不引入新的未解析 import */
    private static final String GOLDEN_ROUTER_INDEX = """
            import { createRouter, createWebHashHistory } from 'vue-router'

            const router = createRouter({
              history: createWebHashHistory(),
              routes: [
                {
                  path: '/',
                  name: 'home',
                  component: { template: '<div style="padding:40px;text-align:center"><h2>项目已生成</h2><p>请补充页面内容</p></div>' }
                }
              ]
            })

            export default router
            """;

    /** 生成期必须保证存在的关键文件 → 黄金模板 */
    private static final String[][] REQUIRED_FILES = {
            {"package.json", GOLDEN_PACKAGE_JSON},
            {"vite.config.js", GOLDEN_VITE_CONFIG},
            {"index.html", GOLDEN_INDEX_HTML},
            {"src/main.js", GOLDEN_MAIN_JS},
            {"src/App.vue", GOLDEN_APP_VUE},
            {"src/router/index.js", GOLDEN_ROUTER_INDEX},
    };

    /**
     * 对生成的 Vue 工程做黄金兜底。
     *
     * @param projectDir 工程根目录
     * @return 本次新补写的相对路径列表（不含已存在未改动的文件），供并入结果 fileNames
     */
    public static List<String> scaffold(File projectDir) {
        List<String> written = new ArrayList<>();
        if (projectDir == null || !projectDir.isDirectory()) {
            return written;
        }
        for (String[] entry : REQUIRED_FILES) {
            String relPath = entry[0];
            if ("package.json".equals(relPath)) {
                written.addAll(ensurePackageJson(projectDir));
            } else {
                written.addAll(writeIfMissing(projectDir, relPath, entry[1]));
            }
        }
        removeRouterConflict(projectDir);
        return written;
    }

    /** package.json：缺/非法 → 整文件黄金模板；合法 → 深度合并修正（保留模型其它依赖） */
    private static List<String> ensurePackageJson(File projectDir) {
        File pkg = FileUtil.file(projectDir, "package.json");
        if (!FileUtil.exist(pkg)) {
            FileUtil.mkParentDirs(pkg);
            FileUtil.writeUtf8String(GOLDEN_PACKAGE_JSON, pkg);
            log.info("VueProjectScaffolder 补齐缺失的 package.json");
            return List.of("package.json");
        }
        try {
            JsonNode tree = MAPPER.readTree(FileUtil.readUtf8String(pkg));
            if (tree == null || !tree.isObject()) {
                FileUtil.writeUtf8String(GOLDEN_PACKAGE_JSON, pkg);
                log.info("VueProjectScaffolder 用黄金模板替换非法 package.json");
                return List.of("package.json");
            }
            ObjectNode root = (ObjectNode) tree;
            boolean changed = false;
            changed |= ensureScripts(root);
            changed |= ensureDependency(root, "dependencies", "vue", "^3.3.4");
            changed |= ensureDependency(root, "dependencies", "vue-router", "^4.2.4");
            changed |= ensureDependency(root, "devDependencies", "vite", "^4.4.5");
            changed |= ensureDependency(root, "devDependencies", "@vitejs/plugin-vue", "^4.2.3");
            if (changed) {
                FileUtil.writeUtf8String(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root), pkg);
                log.info("VueProjectScaffolder 修正 package.json（补 scripts/核心依赖）");
            }
            return List.of();
        } catch (Exception e) {
            // 非法 JSON（含结构异常）→ 整体覆盖为黄金模板
            FileUtil.writeUtf8String(GOLDEN_PACKAGE_JSON, pkg);
            log.info("VueProjectScaffolder 用黄金模板替换非法 package.json：{}", e.getMessage());
            return List.of("package.json");
        }
    }

    /** 确保 scripts.dev/build/preview 存在（保留模型声明的其它 scripts） */
    private static boolean ensureScripts(ObjectNode root) {
        ObjectNode scripts = (ObjectNode) root.get("scripts");
        if (scripts == null) {
            scripts = root.putObject("scripts");
        }
        boolean changed = false;
        changed |= putIfAbsent(scripts, "dev", "vite");
        changed |= putIfAbsent(scripts, "build", "vite build");
        changed |= putIfAbsent(scripts, "preview", "vite preview");
        return changed;
    }

    /** 确保核心依赖存在且 major 版本一致（缺失或版本不对 → 覆盖为固定版本） */
    private static boolean ensureDependency(ObjectNode root, String section, String name, String fixedVersion) {
        ObjectNode deps = (ObjectNode) root.get(section);
        if (deps == null) {
            deps = root.putObject(section);
        }
        JsonNode version = deps.get(name);
        if (version == null || !version.isTextual() || majorOf(version.asText()) != majorOf(fixedVersion)) {
            deps.put(name, fixedVersion);
            return true;
        }
        return false;
    }

    private static boolean putIfAbsent(ObjectNode node, String key, String value) {
        if (node.get(key) == null) {
            node.put(key, value);
            return true;
        }
        return false;
    }

    /** 提取版本字符串的 major 号：^3.3.4 → 3；v4.2 → 4；无法解析 → -1 */
    private static int majorOf(String version) {
        if (version == null) {
            return -1;
        }
        String s = version.trim();
        int i = 0;
        while (i < s.length() && !Character.isDigit(s.charAt(i))) {
            i++;
        }
        int j = i;
        while (j < s.length() && Character.isDigit(s.charAt(j))) {
            j++;
        }
        try {
            return Integer.parseInt(s.substring(i, j));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 只补缺失文件：已存在（模型已写）不覆盖 */
    private static List<String> writeIfMissing(File projectDir, String relPath, String content) {
        File target = FileUtil.file(projectDir, relPath);
        if (FileUtil.exist(target)) {
            return List.of();
        }
        FileUtil.mkParentDirs(target);
        FileUtil.writeUtf8String(content, target);
        log.info("VueProjectScaffolder 补齐缺失文件：{}", relPath);
        return List.of(relPath);
    }

    /** 确定性清理「router.js 与 router/index.js 并存」：统一走 index，避免桩遮蔽真实路由 */
    private static void removeRouterConflict(File projectDir) {
        File routerIndex = FileUtil.file(projectDir, "src/router/index.js");
        File routerFlat = FileUtil.file(projectDir, "src/router.js");
        if (FileUtil.exist(routerIndex) && FileUtil.exist(routerFlat)) {
            FileUtil.del(routerFlat);
            log.info("VueProjectScaffolder 删除冲突的 src/router.js，统一使用 src/router/index.js");
        }
    }
}
