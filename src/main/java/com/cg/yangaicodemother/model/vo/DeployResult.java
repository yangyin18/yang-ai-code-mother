package com.cg.yangaicodemother.model.vo;

import java.time.LocalDateTime;

/**
 * 部署结果：应用被发布到 nginx 后返回给调用方。
 *
 * <p>与保存端 {@code CodeSaveResult} 一致，用 Java record 定义不可变值对象。
 *
 * @param appId        应用 id
 * @param deployKey    部署标识（重复部署复用，访问地址保持稳定）
 * @param deployUrl    部署站点的访问地址，如 http://localhost/apps/a1b2c3d4/
 * @param deployedTime 部署时间
 */
public record DeployResult(
        Long appId,
        String deployKey,
        String deployUrl,
        LocalDateTime deployedTime) {
}
