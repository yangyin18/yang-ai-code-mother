package com.cg.yangaicodemother.service;

import com.cg.yangaicodemother.model.dto.ChatHistoryAdminQueryRequest;
import com.cg.yangaicodemother.model.dto.ChatHistoryCursorRequest;
import com.cg.yangaicodemother.model.dto.ChatHistoryQueryRequest;
import com.cg.yangaicodemother.model.dto.ChatMessageAddRequest;
import com.cg.yangaicodemother.model.dto.ChatSendRequest;
import com.cg.yangaicodemother.model.entity.ChatHistory;
import com.cg.yangaicodemother.model.enums.MessageTypeEnum;
import com.cg.yangaicodemother.model.vo.ChatConversationVO;
import com.cg.yangaicodemother.model.vo.ChatCursorVO;
import com.cg.yangaicodemother.model.vo.ChatHistoryVO;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.service.IService;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

/**
 * 对话历史 服务层。
 *
 * @author 34488
 * @since 2026-08-10
 */
public interface ChatHistoryService extends IService<ChatHistory> {

    /**
     * 查询当前用户的「我的对话」会话列表：每个应用的最新消息摘要，
     * 按最近活跃（应用 updateTime）倒序，上限 50 个。
     * 供「我的对话」列表页展示，点击条目进入对应应用对话页。
     *
     * @param request HttpServletRequest
     * @return 会话摘要列表
     */
    List<ChatConversationVO> getMyConversations(HttpServletRequest request);

    /**
     * 保存一条对话消息（用户）。归属当前登录用户，且只允许应用创建者（或管理员）操作自己的应用。
     * 供前端在用户发送消息、AI 回复失败时调用。
     *
     * @param addRequest 保存请求（appId / messageType / message 均必填）
     * @param request    HttpServletRequest
     * @return 是否成功
     */
    boolean addMessage(ChatMessageAddRequest addRequest, HttpServletRequest request);

    /**
     * 服务端内部保存一条对话消息（不做登录与归属校验）。
     * 供后端在 AI 成功回复 / 回复失败时自动落库，保证对话的完整性。
     *
     * @param appId       所属应用 id
     * @param userId      发送用户 id
     * @param messageType 消息类型（user / ai / error）
     * @param message     消息内容
     * @return 是否成功
     */
    boolean addServerMessage(Long appId, Long userId, MessageTypeEnum messageType, String message);

    /**
     * 服务端内部保存一条对话消息并返回落库记录（不做登录与归属校验）。
     * 供流式对话在 AI 回复成功 / 失败时落库，并把 id / createTime 通过 complete 事件回给前端。
     *
     * @param appId       所属应用 id
     * @param userId      发送用户 id
     * @param messageType 消息类型（ai / error）
     * @param message     消息内容
     * @return 落库后的对话记录
     */
    ChatHistoryVO addServerMessageVO(Long appId, Long userId, MessageTypeEnum messageType, String message);

    /**
     * 保存一条用户消息并返回落库记录（流式对话入口）。
     * 仅应用创建者与管理员可发，写入 messageType = user。
     *
     * @param sendRequest 发送请求（appId / message 必填）
     * @param request     HttpServletRequest
     * @return 落库后的对话记录（含 id / createTime，供前端把消息固定在列表里）
     */
    ChatHistoryVO sendUserMessage(ChatSendRequest sendRequest, HttpServletRequest request);

    /**
     * 组装发给 AI 的对话上下文：应用说明（initPrompt）+ 最近对话 + 当前用户消息。
     * 供流式对话接口在请求 AI 前拼接上下文，保证回复贴合应用背景与对话脉络。
     *
     * @param appId          应用 id
     * @param currentMessage 当前用户消息
     * @return 拼接后的上下文文本
     */
    String buildChatContext(Long appId, String currentMessage);

    /**
     * 游标查询某个应用的对话历史（用户）。keyset 分页：
     * 加载「比 cursorId 更早」的一页（createTime DESC + id DESC），
     * 首次进入不传 cursorId 取最新一页；由前端倒序后升序展示。
     * 每页默认 10 条、最多 20 条。仅应用创建者与管理员可见。
     *
     * @param cursorRequest 游标请求（appId / cursorId / size）
     * @param request       HttpServletRequest
     * @return 本页消息 + 是否还有更早
     */
    ChatCursorVO getChatHistoryByCursor(ChatHistoryCursorRequest cursorRequest, HttpServletRequest request);

    /**
     * 分页查询某个应用的对话历史（用户）。按创建时间倒序（最新在前），
     * 每页默认 10 条、最多 20 条。仅应用创建者与管理员可见。
     *
     * @param queryRequest 分页 + 过滤条件
     * @param request      HttpServletRequest
     * @return 分页结果
     */
    Page<ChatHistoryVO> getChatHistoryPage(ChatHistoryQueryRequest queryRequest, HttpServletRequest request);

    /**
     * 分页查询全部应用的对话历史（管理员）。按创建时间倒序，便于内容监管。
     *
     * @param queryRequest 分页 + 过滤条件
     * @return 分页结果
     */
    Page<ChatHistoryVO> adminGetChatHistoryPage(ChatHistoryAdminQueryRequest queryRequest);

    /**
     * 删除某应用的全部对话历史（逻辑删除）。
     * 供删除应用时级联调用，避免数据冗余。
     *
     * @param appId 应用 id
     * @return 是否成功
     */
    boolean removeByAppId(Long appId);

}
