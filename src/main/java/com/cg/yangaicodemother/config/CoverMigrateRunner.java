package com.cg.yangaicodemother.config;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.cg.yangaicodemother.mapper.AppCoverMapper;
import com.cg.yangaicodemother.mapper.AppMapper;
import com.cg.yangaicodemother.model.entity.AppCover;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.LocalDateTime;

/**
 * 存量封面一次性迁移：把改版前落在磁盘 {code.cover.root}/{appId}.png 的截图
 * 导入 DB（app_cover 表）。幂等：app_cover 已有该行则跳过并清理磁盘残留，
 * 应用已不存在（含逻辑删除）不导入。启动时跑一次即完成迁移。
 */
@Slf4j
@Component
public class CoverMigrateRunner implements CommandLineRunner {

    /** 合法封面 PNG 的最小字节数（与 CoverServiceImpl 一致） */
    private static final long MIN_PNG_BYTES = 3072;

    /** 封面截图暂存目录（code.cover.root，升级前是持久存储） */
    private final String coverRoot;

    private final AppMapper appMapper;

    private final AppCoverMapper appCoverMapper;

    public CoverMigrateRunner(
            @Value("${code.cover.root:${user.dir}/tmp/covers}") String coverRoot,
            AppMapper appMapper,
            AppCoverMapper appCoverMapper) {
        this.coverRoot = coverRoot;
        this.appMapper = appMapper;
        this.appCoverMapper = appCoverMapper;
    }

    @Override
    public void run(String... args) {
        File dir = new File(coverRoot);
        File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".png"));
        if (files == null || files.length == 0) {
            return;
        }
        int imported = 0;
        for (File file : files) {
            String base = file.getName();
            String appIdText = base.substring(0, base.length() - 4);
            // 文件名必须是纯数字 appId（雪花 ID），否则跳过
            Long appId = StrUtil.isNumeric(appIdText) ? Long.valueOf(appIdText) : null;
            if (appId == null) {
                continue;
            }
            // 应用存在且未删除才导入；app_cover 已有该行则跳过（幂等）
            if (appMapper.selectOneById(appId) == null) {
                continue;
            }
            if (appCoverMapper.selectOneById(appId) != null) {
                FileUtil.del(file);
                continue;
            }
            byte[] bytes = FileUtil.readBytes(file);
            if (bytes == null || bytes.length < MIN_PNG_BYTES) {
                continue;
            }
            AppCover cover = new AppCover();
            cover.setAppId(appId);
            cover.setImage(bytes);
            cover.setUpdateTime(LocalDateTime.now());
            appCoverMapper.insert(cover);
            FileUtil.del(file);
            imported++;
            log.info("存量封面已导入 DB：appId={}, size={} bytes", appId, bytes.length);
        }
        if (imported > 0) {
            log.info("封面迁移完成：共导入 {} 张", imported);
        }
    }
}
