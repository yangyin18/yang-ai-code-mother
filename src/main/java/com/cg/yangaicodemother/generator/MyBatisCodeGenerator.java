package com.cg.yangaicodemother.generator;

import cn.hutool.core.lang.Dict;
import cn.hutool.core.util.StrUtil;
import cn.hutool.setting.yaml.YamlUtil;
import com.mybatisflex.codegen.Generator;
import com.mybatisflex.codegen.config.GlobalConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.util.Map;

/**
 * MyBatis-Flex 代码生成器。
 *
 * <p>自动读取 {@code src/main/resources/application.yml} 中的 {@code spring.datasource} 配置连接数据库，
 * 扫描库内全部业务表，生成 entity / mapper / service / serviceImpl 四层代码，
 * 输出到 {@code com.cg.yangaicodemother} 根包下（IDE 中直接运行 main 方法即可）。</p>
 *
 * <p>默认覆盖已生成的文件，可以放心重复执行。</p>
 */
public class MyBatisCodeGenerator {

    private MyBatisCodeGenerator() {
    }

    public static void main(String[] args) {
        try (HikariDataSource dataSource = buildDataSource()) {
            GlobalConfig globalConfig = createGlobalConfig();
            Generator generator = new Generator(dataSource, globalConfig);
            generator.generate();
            System.out.println(">>> 代码生成完成，请刷新 IDE 后查看 src/main/java/com/cg/yangaicodemother 目录");
        }
    }

    /**
     * 从 application.yml 读取数据源配置并构建 Hikari 数据源。
     * 避免在生成器代码里硬编码数据库地址 / 账号 / 密码。
     */
    public static HikariDataSource buildDataSource() {
        Dict dict = YamlUtil.loadByPath("application.yml");
        Map<String, Object> dataSourceConfig = dict.getByPath("spring.datasource");
        if (dataSourceConfig == null || dataSourceConfig.isEmpty()) {
            throw new IllegalStateException("application.yml 中缺少 spring.datasource 配置");
        }

        String url = requireConfig(dataSourceConfig, "url");
        String username = requireConfig(dataSourceConfig, "username");
        String password = requireConfig(dataSourceConfig, "password");
        String driverClassName = requireConfig(dataSourceConfig, "driver-class-name");

        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setDriverClassName(driverClassName);
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        return dataSource;
    }

    private static String requireConfig(Map<String, Object> config, String key) {
        Object value = config.get(key);
        if (value == null) {
            throw new IllegalStateException("spring.datasource." + key + " 未配置");
        }
        return String.valueOf(value);
    }

    public static GlobalConfig createGlobalConfig() {
        // 根包名与项目包结构保持一致
        String basePackage = "com.cg.yangaicodemother";
        String author = StrUtil.blankToDefault(System.getProperty("user.name"), "yang-ai-code-mother");

        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setBasePackage(basePackage);
        globalConfig.setSourceDir("src/main/java");
        // 实体放到 model.entity 包，与项目现有结构保持一致
        globalConfig.setEntityPackage("model.entity");
        globalConfig.setAuthor(author);

        // ============ 生成范围：默认扫描库内全部业务表，需要过滤时取消注释 ============
        // globalConfig.setTablePrefix("tb_");
        // globalConfig.setGenerateTable("user");

        // ============ Entity（Lombok + JDK21） ============
        globalConfig.enableEntity()
                .setWithLombok(true)
                .setJdkVersion(21)
                .setOverwriteEnable(true);

        // ============ Mapper（自动标注 @Mapper，免写 @MapperScan） ============
        globalConfig.enableMapper()
                .setMapperAnnotation(true)
                .setOverwriteEnable(true);

        // ============ Service / ServiceImpl ============
        globalConfig.enableService().setOverwriteEnable(true);
        globalConfig.enableServiceImpl().setOverwriteEnable(true);

        // 各包下生成 package-info.java 包注释
        globalConfig.enablePackageInfo();

        // ============ 公共列约定 ============
        // 逻辑删除：生成的实体上 isDelete 字段自动标注 @Column(isLogicDelete = true)
        globalConfig.setLogicDeleteColumn("isDelete");
        // 乐观锁：若表里有 version 字段，取消下一行注释
        // globalConfig.setVersionColumn("version");

        return globalConfig;
    }
}
