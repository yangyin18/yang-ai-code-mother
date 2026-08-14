package com.cg.yangaicodemother.service;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 应用封面服务：把应用对话页 /chat/:appId 的截图作为「我的应用」封面。
 *
 * <p>截图用已安装的 Headless Chrome（puppeteer-core 脚本）完成，会话 cookie 取自
 * 当前登录用户（应用属主）的请求，无需额外机器人账号。截图在后台异步执行，
 * 任何失败只记日志，绝不影响生成 / 部署主流程。PNG 字节持久化到 DB（app_cover 表），
 * 磁盘仅作截图脚本的临时暂存。</p>
 */
public interface CoverService {

    /**
     * 异步刷新应用封面（生成对话页截图并写回 app.cover 与 app_cover 表）。
     *
     * <p>仅记录日志，不抛异常；同一 appId 已有截图任务在跑时幂等跳过。
     * 会从请求同步读取 JSESSIONID cookie，之后的任务线程只依赖提取出的值，
     * 不持有 request，避免请求在异步完成后被容器回收。
     *
     * @param appId   应用 id
     * @param request 当前请求（需携带属主登录态），可为 null（跳过）
     */
    void refreshCoverAsync(Long appId, HttpServletRequest request);

    /**
     * 读取应用封面 PNG 字节（来自 DB app_cover 表）。
     *
     * @param appId 应用 id
     * @return 封面 PNG 字节（无记录返回 null）；调用方负责权限校验
     */
    byte[] getCoverBytes(Long appId);

    /**
     * 删除应用的封面记录（删除应用时级联清理，避免 DB 残留孤儿 blob）。
     *
     * @param appId 应用 id
     */
    void deleteCover(Long appId);
}
