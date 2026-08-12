package com.cg.yangaicodemother.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Vue 工程「缺失引用」轻量修复：大模型生成的工程偶尔会引用不存在的文件
 * （典型如路由里 {@code import About from '../pages/About.vue'} 但 About.vue 没生成），
 * 直接 {@code npm run build} 会报 ENOENT 失败。本类在构建前扫描源码里的相对导入，
 * 对指向不存在的目标文件，生成一个最小可用的占位文件，让构建能继续。
 *
 * <p>占位策略：
 * <ul>
 *   <li>{@code .vue} 缺失 → 可见占位 SFC（页面渲染出「待完善模块」提示而非空白）；</li>
 *   <li>{@code .js/.ts/...} 缺失 → 按导入名补命名导出的 {@code noop}（可调用、可访问属性、
 *       永不抛错的 Proxy 对象），避免 rollup「X is not exported」报错，也避免运行期因
 *       {@code undefined} 访问而崩溃。</li>
 * </ul>
 *
 * <p>只处理「相对导入」（{@code ./}、{@code ../}、{@code @/} 别名），
 * 不改动裸模块名（vue / vue-router 等由 npm 提供）。幂等：已存在的文件不覆盖。
 * 每次 {@link #repair} 使用局部状态，可安全并发调用。
 */
@Slf4j
public final class VueImportRepairer {

    private VueImportRepairer() {
    }

    /** 支持扫描的源码扩展名 */
    private static final String[] SOURCE_EXT = {".vue", ".js", ".mjs", ".ts", ".jsx", ".tsx"};

    /**
     * 无扩展名相对导入的候选解析后缀，顺序贴近 vite（默认扩展名 + Vue 插件 + 目录 index）。
     * 用于两处：先探测「是否已存在可解析文件」（存在则不算缺失、不补桩，
     * 避免补出 {@code router.js} 遮蔽真实 {@code router/index.js} 的 Bug）；
     * 探测全部失败后再补第一个可解析的桩。
     */
    private static final String[] RESOLVE_SUFFIXES = {
            "", ".vue", ".js", ".mjs", ".ts", ".jsx", ".tsx",
            "/index.vue", "/index.js", "/index.ts"
    };

    /** import { a, b as c } from './x'（可含默认导入：import Foo, { a } from './x'） */
    private static final Pattern NAMED_FROM = Pattern.compile(
            "import\\s+(?:[\\w$]+\\s*,)?\\s*\\{([^}]*)\\}\\s*from\\s*['\"]([^'\"]+)['\"]");

    /** import Foo from './x'（纯默认导入） */
    private static final Pattern DEFAULT_FROM = Pattern.compile(
            "import\\s+[\\w$]+\\s+from\\s*['\"]([^'\"]+)['\"]");

    /** export { a, b } from './x' */
    private static final Pattern EXPORT_FROM = Pattern.compile(
            "export\\s+[^;{]*\\{([^}]*)\\}\\s*from\\s*['\"]([^'\"]+)['\"]");

    /** import('./x') 动态导入 */
    private static final Pattern DYNAMIC_IMPORT = Pattern.compile(
            "import\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");

    /** require('./x') */
    private static final Pattern REQUIRE = Pattern.compile(
            "\\brequire\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");

    /**
     * 扫描 {@code sourceDir} 下所有源码文件，补齐缺失的相对导入目标。
     * 跳过 node_modules / dist / .git。返回补齐的文件数（供日志）。
     */
    public static int repair(File sourceDir) {
        if (sourceDir == null || !sourceDir.isDirectory()) {
            return 0;
        }
        Map<String, Set<String>> missing = new LinkedHashMap<>();
        scanDir(sourceDir, sourceDir, missing);
        int created = 0;
        for (Map.Entry<String, Set<String>> e : missing.entrySet()) {
            File target = FileUtil.file(sourceDir, e.getKey());
            if (FileUtil.exist(target)) {
                continue;
            }
            FileUtil.mkParentDirs(target);
            String content = e.getKey().endsWith(".vue")
                    ? vueStub()
                    : stubJs(e.getValue());
            FileUtil.writeUtf8String(content, target);
            created++;
            log.info("VueImportRepairer 补齐缺失文件：{}", target.getAbsolutePath());
        }
        return created;
    }

    /** 递归扫描目录，收集每个源码文件里的相对导入引用 */
    private static void scanDir(File root, File dir, Map<String, Set<String>> missing) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                String name = f.getName();
                if ("node_modules".equals(name) || "dist".equals(name) || ".git".equals(name)) {
                    continue;
                }
                scanDir(root, f, missing);
            } else if (isSourceFile(f.getName())) {
                collect(root, f, missing);
            }
        }
    }

    private static boolean isSourceFile(String name) {
        for (String ext : SOURCE_EXT) {
            if (name.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /** 解析单个文件里的导入语句，把相对导入解析成相对 root 的目标路径，缺失则登记 */
    private static void collect(File root, File file, Map<String, Set<String>> missing) {
        String content;
        try {
            content = FileUtil.readUtf8String(file);
        } catch (Exception e) {
            return;
        }
        if (StrUtil.isBlank(content)) {
            return;
        }
        String dir = file.getParentFile().getAbsolutePath();
        Set<String> refs = new LinkedHashSet<>();
        addFromMatches(refs, content, NAMED_FROM);
        addFromMatches(refs, content, DEFAULT_FROM);
        addFromMatches(refs, content, EXPORT_FROM);
        addSinglePath(refs, content, DYNAMIC_IMPORT);
        addSinglePath(refs, content, REQUIRE);

        // 该文件里所有命名导入的标识符（用于给缺失模块补命名导出）
        Set<String> namedSpecifiers = new LinkedHashSet<>();
        addNamedSpecifiers(namedSpecifiers, content);

        for (String ref : refs) {
            if (!isRelative(ref)) {
                continue;
            }
            String stubPath = canonicalStubPath(root, dir, ref);
            if (stubPath == null) {
                continue;
            }
            if (FileUtil.exist(FileUtil.file(root, stubPath))) {
                continue;
            }
            // vite 已能按扩展名/目录 index 解析到现有文件（如 ./router → router/index.js），
            // 视为引用有效，不补桩 —— 否则补出的 router.js 会遮蔽真实文件导致构建/运行异常
            if (resolvesToExisting(root, dir, ref)) {
                continue;
            }
            missing.computeIfAbsent(stubPath, k -> new LinkedHashSet<>()).addAll(namedSpecifiers);
        }
    }

    /**
     * 探测无扩展名的相对导入是否已被 vite 解析到某个现有文件。
     * 命中任一候选（扩展名或目录 index）即返回 true，表示引用不缺失。
     */
    private static boolean resolvesToExisting(File root, String fileDir, String ref) {
        String clean = stripQuery(ref);
        File base;
        if (clean.startsWith("@/")) {
            base = new File(new File(root, "src"), clean.substring(2));
        } else {
            base = new File(fileDir, clean);
        }
        for (String suffix : RESOLVE_SUFFIXES) {
            if (FileUtil.exist(new File(base.getAbsolutePath() + suffix))) {
                return true;
            }
        }
        return false;
    }

    private static void addFromMatches(Set<String> out, String content, Pattern p) {
        Matcher m = p.matcher(content);
        while (m.find()) {
            // NAMED_FROM / EXPORT_FROM 有 2 组（1=标识符，2=路径），DEFAULT_FROM 只有 1 组（路径）
            out.add(m.group(m.groupCount() == 2 ? 2 : 1));
        }
    }

    private static void addSinglePath(Set<String> out, String content, Pattern p) {
        Matcher m = p.matcher(content);
        while (m.find()) {
            out.add(m.group(1));
        }
    }

    private static boolean isRelative(String ref) {
        return ref.startsWith("./") || ref.startsWith("../") || ref.startsWith("@/");
    }

    /**
     * 把导入引用解析成「相对 root 的目标路径」，并统一成带扩展名的 stub 路径：
     * 引用自带扩展名直接用；无扩展名按 {@code .vue} 补齐（页面/组件最常见）。
     * 别名 {@code @/} 视为 {@code src/}（vite 默认 alias 配置，见 codegen-vue 的 vite.config.js）。
     */
    private static String canonicalStubPath(File root, String fileDir, String ref) {
        String clean = stripQuery(ref);
        String base;
        if (clean.startsWith("@/")) {
            base = new File(new File(root, "src"), clean.substring(2)).getAbsolutePath();
        } else {
            base = new File(fileDir, clean).getAbsolutePath();
        }
        // 引用自带扩展名直接用；否则按 .js 补齐（vite 默认按 .js 优先解析，
        // 无扩展名的裸相对导入多是工具/数据模块；组件通常都写全 .vue 扩展名）
        File target = new File(base + (hasSourceExt(clean) ? "" : ".js"));
        return root.toPath().relativize(target.toPath()).toString().replace('\\', '/');
    }

    private static boolean hasSourceExt(String path) {
        for (String ext : SOURCE_EXT) {
            if (path.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private static String stripQuery(String ref) {
        int q = ref.indexOf('?');
        return q >= 0 ? ref.substring(0, q) : ref;
    }

    /** 收集命名导入的标识符：{ a, b as c, d } → a、c、d */
    private static void addNamedSpecifiers(Set<String> names, String content) {
        Matcher m = NAMED_FROM.matcher(content);
        while (m.find()) {
            String spec = m.group(1);
            for (String item : spec.split(",")) {
                String t = item.trim();
                if (StrUtil.isBlank(t)) {
                    continue;
                }
                // a as c → c；a → a
                int as = t.lastIndexOf(" as ");
                names.add(as >= 0 ? t.substring(as + 4).trim() : t);
            }
        }
    }

    /** 缺失组件 → 可见占位 SFC：保留自动补齐标记，页面渲染出提示而非空白 */
    private static String vueStub() {
        return "<!-- 自动补齐：被导入但缺失的组件 -->\n"
                + "<template>\n"
                + "  <div class=\"stub-fallback\">\n"
                + "    <h3>待完善模块</h3>\n"
                + "    <p>此组件尚未生成完整内容，展示占位。</p>\n"
                + "  </div>\n"
                + "</template>\n"
                + "<script>\nexport default { name: 'StubFallback' }\n</script>\n";
    }

    /**
     * 缺失模块 → noop 桩：命名导出与 default 都是同一个 Proxy 包装的函数，
     * {@code get/apply/construct/set} 全部命中后返回自身，任何调用/属性访问都不会抛错。
     */
    private static String stubJs(Set<String> named) {
        StringBuilder sb = new StringBuilder();
        sb.append("// 自动补齐：被导入但缺失的模块\n");
        sb.append("const noop = new Proxy(function () {}, {\n");
        sb.append("  get: () => noop,\n");
        sb.append("  apply: () => noop,\n");
        sb.append("  construct: () => noop,\n");
        sb.append("  set: () => true\n");
        sb.append("});\n");
        for (String name : named) {
            if (name.matches("[\\w$]+")) {
                sb.append("export const ").append(name).append(" = noop;\n");
            }
        }
        sb.append("export default noop;\n");
        return sb.toString();
    }
}
