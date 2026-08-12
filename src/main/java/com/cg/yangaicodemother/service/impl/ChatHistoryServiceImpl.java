package com.cg.yangaicodemother.service.impl;

import cn.hutool.core.util.StrUtil;
import com.cg.yangaicodemother.common.PageRequest;
import com.cg.yangaicodemother.core.saver.CodeSaver;
import com.cg.yangaicodemother.exception.BusinessException;
import com.cg.yangaicodemother.exception.ErrorCode;
import com.cg.yangaicodemother.mapper.AppMapper;
import com.cg.yangaicodemother.mapper.ChatHistoryMapper;
import com.cg.yangaicodemother.model.dto.ChatHistoryAdminQueryRequest;
import com.cg.yangaicodemother.model.dto.ChatHistoryCursorRequest;
import com.cg.yangaicodemother.model.dto.ChatHistoryQueryRequest;
import com.cg.yangaicodemother.model.dto.ChatMessageAddRequest;
import com.cg.yangaicodemother.model.dto.ChatSendRequest;
import com.cg.yangaicodemother.model.entity.App;
import com.cg.yangaicodemother.model.entity.ChatHistory;
import com.cg.yangaicodemother.model.enums.MessageTypeEnum;
import com.cg.yangaicodemother.model.enums.UserRoleEnum;
import com.cg.yangaicodemother.model.vo.ChatConversationVO;
import com.cg.yangaicodemother.model.vo.ChatCursorVO;
import com.cg.yangaicodemother.model.vo.ChatHistoryVO;
import com.cg.yangaicodemother.model.vo.LoginUserVO;
import com.cg.yangaicodemother.service.ChatHistoryService;
import com.cg.yangaicodemother.service.UserService;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryColumn;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.util.LambdaGetter;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 对话历史 服务层实现。
 *
 * @author 34488
 * @since 2026-08-10
 */
@Slf4j
@Service
public class ChatHistoryServiceImpl extends ServiceImpl<ChatHistoryMapper, ChatHistory> implements ChatHistoryService {

    /**
     * 用户侧每页最多 20 条（需求限制），超过则截断；默认每页 10 条（消息加载基准）。
     */
    private static final int USER_PAGE_SIZE_LIMIT = 20;

    /**
     * 允许排序的字段白名单：管理员列表前端传 sortField 时只允许映射到这里的实体字段，
     * 防止把任意字符串拼进 ORDER BY 造成 SQL 注入。
     */
    private static final Map<String, LambdaGetter<ChatHistory>> SORT_FIELD_MAP = Map.of(
            "id", ChatHistory::getId,
            "appId", ChatHistory::getAppId,
            "userId", ChatHistory::getUserId,
            "messageType", ChatHistory::getMessageType,
            "createTime", ChatHistory::getCreateTime,
            "updateTime", ChatHistory::getUpdateTime
    );

    @Resource
    private UserService userService;

    /**
     * 归属校验直接查 AppMapper 而非注入 AppService：
     * AppServiceImpl 需要调用本服务的 removeByAppId（删除应用时级联删对话），
     * 若这里再注入 AppService 会形成循环依赖，故只依赖下层的 Mapper。
     */
    @Resource
    private AppMapper appMapper;

    /** 部署站点公网前缀，用于给会话摘要拼已部署应用的访问地址 */
    @Value("${code.deploy.base-url:}")
    private String deployBaseUrl;

    // ==================== 用户端：保存与查询 ====================

    @Override
    public boolean addMessage(ChatMessageAddRequest addRequest, HttpServletRequest request) {
        LoginUserVO loginUser = userService.getLoginUser(request);
        if (addRequest == null || addRequest.getAppId() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 id 不能为空");
        }
        MessageTypeEnum type = validateType(addRequest.getMessageType());
        if (StrUtil.isBlank(addRequest.getMessage())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "消息内容不能为空");
        }
        // 只允许应用创建者（或管理员）往应用里写消息
        checkAppPermission(addRequest.getAppId(), loginUser);
        return saveMessage(addRequest.getAppId(), loginUser.getId(), type, addRequest.getMessage()) != null;
    }

    @Override
    public boolean addServerMessage(Long appId, Long userId, MessageTypeEnum messageType, String message) {
        if (appId == null || userId == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 id 与用户 id 不能为空");
        }
        if (messageType == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "消息类型不能为空");
        }
        if (StrUtil.isBlank(message)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "消息内容不能为空");
        }
        return saveMessage(appId, userId, messageType, message) != null;
    }

    @Override
    public ChatHistoryVO addServerMessageVO(Long appId, Long userId, MessageTypeEnum messageType, String message) {
        if (appId == null || userId == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 id 与用户 id 不能为空");
        }
        if (messageType == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "消息类型不能为空");
        }
        if (StrUtil.isBlank(message)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "消息内容不能为空");
        }
        ChatHistory saved = saveMessage(appId, userId, messageType, message);
        return saved == null ? null : toVO(saved);
    }

    @Override
    public ChatHistoryVO sendUserMessage(ChatSendRequest sendRequest, HttpServletRequest request) {
        LoginUserVO loginUser = userService.getLoginUser(request);
        if (sendRequest == null || sendRequest.getAppId() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 id 不能为空");
        }
        if (StrUtil.isBlank(sendRequest.getMessage())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "消息内容不能为空");
        }
        // 只允许应用创建者（或管理员）在应用里发消息
        checkAppPermission(sendRequest.getAppId(), loginUser);

        ChatHistory chatHistory = new ChatHistory();
        chatHistory.setAppId(sendRequest.getAppId());
        chatHistory.setUserId(loginUser.getId());
        chatHistory.setMessageType(MessageTypeEnum.USER.getValue());
        chatHistory.setMessage(sendRequest.getMessage());
        if (!this.save(chatHistory)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "消息保存失败");
        }
        return toVO(chatHistory);
    }

    @Override
    public String buildChatContext(Long appId, String currentMessage) {
        App app = appMapper.selectOneById(appId);
        if (app == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        }
        StringBuilder sb = new StringBuilder();
        if (StrUtil.isNotBlank(app.getInitPrompt())) {
            sb.append("应用说明：").append(app.getInitPrompt()).append('\n');
        }
        // 最近 10 条用户消息（新→旧），翻转成 旧→新，便于 AI 理解对话脉络。
        // 只取「用户」消息、跳过「助手」回复：本上下文只用于代码重新生成，
        // 若把助手上一轮的澄清/解释文字也拼进去，代码生成模型容易跟着输出
        // 对话性文本（如「好的，请告诉我你想要什么类型…」）而不是代码，导致解析失败。
        // 注意：这里必须用字符串列名（new QueryColumn(...)）而非 lambda 方法引用。
        // 本方法跑在 AI 流式回调线程上，该线程的上下文类加载器在 Spring Boot 可执行 jar
        // 下读不到 BOOT-INF/classes 里的实体类，MyBatis-Flex 的 lambda 查询内部用
        // Class.forName 解析实体类会抛 ClassNotFoundException；字符串列名不经 lambda
        // 序列化、不依赖类加载器，任意线程都能安全执行。
        List<ChatHistory> recent = this.list(QueryWrapper.create()
                .where(new QueryColumn("appId").eq(appId))
                .where(new QueryColumn("messageType").eq(MessageTypeEnum.USER.getValue()))
                .orderBy("createTime", false)
                .orderBy("id", false)
                .limit(10));
        Collections.reverse(recent);
        if (!recent.isEmpty()) {
            sb.append("\n用户近期需求：\n");
            for (ChatHistory h : recent) {
                sb.append("- ").append(StrUtil.maxLength(h.getMessage(), 200)).append('\n');
            }
        }
        sb.append("\n用户最新消息：").append(StrUtil.maxLength(currentMessage, 500));
        // 附上当前已生成代码作为「小幅度修改」基线：模型看到原代码才能只改目标元素/局部，
        // 而不是凭记忆整页重写（这是对话页「选中元素改文字」真正落地的关键）
        String codeContext = CodeSaver.buildCurrentCodeContext(app.getCodeGenType(), app.getId());
        if (StrUtil.isNotBlank(codeContext)) {
            sb.append(codeContext);
        }
        return sb.toString();
    }

    @Override
    public List<ChatConversationVO> getMyConversations(HttpServletRequest request) {
        LoginUserVO loginUser = userService.getLoginUser(request);
        // 1. 当前用户的应用（最近活跃优先，上限 50）
        List<App> myApps = appMapper.selectListByQuery(QueryWrapper.create()
                .where(App::getUserId).eq(loginUser.getId())
                .orderBy(App::getUpdateTime, false)
                .limit(50));
        if (myApps.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> appIds = myApps.stream().map(App::getId).toList();
        // 2. 批量查这些应用的全部对话（新→旧），Java 侧按应用分组取最新一条 + 计数
        List<ChatHistory> histories = this.list(QueryWrapper.create()
                .where(ChatHistory::getAppId).in(appIds)
                .orderBy(ChatHistory::getCreateTime, false)
                .orderBy(ChatHistory::getId, false));
        Map<Long, List<ChatHistory>> byApp = histories.stream()
                .collect(Collectors.groupingBy(ChatHistory::getAppId));

        List<ChatConversationVO> result = new ArrayList<>(myApps.size());
        for (App app : myApps) {
            List<ChatHistory> msgs = byApp.getOrDefault(app.getId(), Collections.emptyList());
            ChatConversationVO vo = new ChatConversationVO();
            vo.setAppId(app.getId());
            vo.setAppName(app.getAppName());
            vo.setCover(app.getCover());
            vo.setMessageCount((long) msgs.size());
            // 已部署应用拼出 nginx 访问地址，供前端跳转
            vo.setDeployUrl(StrUtil.isNotBlank(app.getDeployKey()) && StrUtil.isNotBlank(deployBaseUrl)
                    ? deployBaseUrl + "/" + app.getDeployKey() + "/" : null);
            if (!msgs.isEmpty()) {
                ChatHistory latest = msgs.get(0);
                vo.setLatestMessage(StrUtil.maxLength(latest.getMessage(), 80));
                vo.setLatestMessageType(latest.getMessageType());
                vo.setLatestTime(latest.getCreateTime());
            }
            result.add(vo);
        }
        return result;
    }

    @Override
    public Page<ChatHistoryVO> getChatHistoryPage(ChatHistoryQueryRequest queryRequest, HttpServletRequest request) {
        LoginUserVO loginUser = userService.getLoginUser(request);
        if (queryRequest == null || queryRequest.getAppId() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 id 不能为空");
        }
        // 仅应用创建者与管理员可见，浏览他人应用不会泄露对话
        checkAppPermission(queryRequest.getAppId(), loginUser);

        QueryWrapper queryWrapper = QueryWrapper.create();
        queryWrapper.where(ChatHistory::getAppId).eq(queryRequest.getAppId());
        // 时间倒序 + id 倒序做稳定排序，保证分页翻页不重不漏（同一秒内的消息按 id 再排）
        queryWrapper.orderBy(ChatHistory::getCreateTime, false)
                .orderBy(ChatHistory::getId, false);
        return toVOPage(pageByRequest(queryRequest, USER_PAGE_SIZE_LIMIT, queryWrapper));
    }

    @Override
    public ChatCursorVO getChatHistoryByCursor(ChatHistoryCursorRequest cursorRequest, HttpServletRequest request) {
        LoginUserVO loginUser = userService.getLoginUser(request);
        if (cursorRequest == null || cursorRequest.getAppId() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 id 不能为空");
        }
        // 仅应用创建者与管理员可见，浏览他人应用不会泄露对话
        checkAppPermission(cursorRequest.getAppId(), loginUser);

        int size = cursorRequest.getSize() == null ? 10 : cursorRequest.getSize();
        if (size <= 0) {
            size = 10;
        }
        size = Math.min(size, USER_PAGE_SIZE_LIMIT);

        QueryWrapper queryWrapper = QueryWrapper.create();
        queryWrapper.where(ChatHistory::getAppId).eq(cursorRequest.getAppId());
        // keyset 游标：只取比 cursorId 更早的消息；首次加载不传即最新一页
        if (cursorRequest.getCursorId() != null) {
            queryWrapper.where(ChatHistory::getId).lt(cursorRequest.getCursorId());
        }
        // 时间倒序 + id 倒序做稳定排序；多取一条判断是否还有更早
        queryWrapper.orderBy(ChatHistory::getCreateTime, false)
                .orderBy(ChatHistory::getId, false)
                .limit(size + 1);
        List<ChatHistory> list = this.list(queryWrapper);
        boolean hasMore = list.size() > size;
        List<ChatHistoryVO> records = (hasMore ? list.subList(0, size) : list).stream()
                .map(this::toVO)
                .collect(Collectors.toList());
        return new ChatCursorVO(records, hasMore);
    }

    // ==================== 管理端：内容监管 ====================

    @Override
    public Page<ChatHistoryVO> adminGetChatHistoryPage(ChatHistoryAdminQueryRequest queryRequest) {
        if (queryRequest == null) {
            queryRequest = new ChatHistoryAdminQueryRequest();
        }
        QueryWrapper queryWrapper = QueryWrapper.create();
        // 条件过滤：传了才拼，没传不拼
        if (queryRequest.getId() != null) {
            queryWrapper.where(ChatHistory::getId).eq(queryRequest.getId());
        }
        if (queryRequest.getAppId() != null) {
            queryWrapper.where(ChatHistory::getAppId).eq(queryRequest.getAppId());
        }
        if (queryRequest.getUserId() != null) {
            queryWrapper.where(ChatHistory::getUserId).eq(queryRequest.getUserId());
        }
        if (StrUtil.isNotBlank(queryRequest.getMessageType())) {
            // 校验消息类型取值，防止拼出脏查询条件
            if (MessageTypeEnum.getEnumByValue(queryRequest.getMessageType()) == null) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "非法的消息类型");
            }
            queryWrapper.where(ChatHistory::getMessageType).eq(queryRequest.getMessageType());
        }
        // 排序：白名单映射，未知字段回退按创建时间倒序
        // 注意：Map.of 的不可变 Map 在 get(null) 时会抛 NPE，必须先判空
        LambdaGetter<ChatHistory> sortColumn = StrUtil.isNotBlank(queryRequest.getSortField())
                ? SORT_FIELD_MAP.get(queryRequest.getSortField())
                : null;
        if (sortColumn != null) {
            boolean asc = "ascend".equalsIgnoreCase(queryRequest.getSortOrder());
            queryWrapper.orderBy(sortColumn, asc);
        } else {
            queryWrapper.orderBy(ChatHistory::getCreateTime, false);
        }
        // 管理员分页：每页数量不限，直接用请求里的 pageSize
        return toVOPage(pageByRequest(queryRequest, Integer.MAX_VALUE, queryWrapper));
    }

    // ==================== 级联删除 ====================

    @Override
    public boolean removeByAppId(Long appId) {
        if (appId == null) {
            return false;
        }
        // mybatis-flex 逻辑删除：isDelete 标注了 @Column(isLogicDelete=true)，删后查询自动过滤
        return this.remove(QueryWrapper.create().where(ChatHistory::getAppId).eq(appId));
    }

    // ==================== 私有工具 ====================

    /** 校验消息类型并返回枚举，非法取值抛参数错误 */
    private MessageTypeEnum validateType(String messageType) {
        if (StrUtil.isBlank(messageType)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "消息类型不能为空");
        }
        MessageTypeEnum type = MessageTypeEnum.getEnumByValue(messageType);
        if (type == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "非法的消息类型");
        }
        return type;
    }

    /** 校验当前用户对某应用有查看 / 写入对话的权限：应用创建者或管理员 */
    private void checkAppPermission(Long appId, LoginUserVO loginUser) {
        App app = appMapper.selectOneById(appId);
        if (app == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        }
        if (UserRoleEnum.ADMIN.getValue().equals(loginUser.getUserRole())) {
            return;
        }
        if (app.getUserId().equals(loginUser.getId())) {
            return;
        }
        throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限访问该应用的对话历史");
    }

    /** 实际落库：按消息类型写入一条对话记录，返回落库实体（含 id / createTime）。
     *  createTime 由 DB CURRENT_TIMESTAMP 生成但插入后实体不回填，这里显式赋值，
     *  保证 SSE complete 事件 / VO 能带上真实时间（否则前端新消息气泡时间显示空白）。
     *  落库成功后顺带刷新应用的更新时间，让「我的应用」按最近对话活跃度置顶。 */
    private ChatHistory saveMessage(Long appId, Long userId, MessageTypeEnum messageType, String message) {
        LocalDateTime now = LocalDateTime.now();
        ChatHistory chatHistory = new ChatHistory();
        chatHistory.setAppId(appId);
        chatHistory.setUserId(userId);
        chatHistory.setMessageType(messageType.getValue());
        chatHistory.setMessage(message);
        chatHistory.setCreateTime(now);
        chatHistory.setUpdateTime(now);
        if (!this.save(chatHistory)) {
            return null;
        }
        touchAppUpdateTime(appId, now);
        return chatHistory;
    }

    /** 对话有消息落库时刷新应用更新时间（「我的应用」列表按此字段倒序置顶）。
     *  MyBatis-Flex 的 update(entity) 默认只更新非空字段，这里只带 id + updateTime，
     *  不会覆盖应用的其它列；逻辑删除（isDelete）自动拼进 WHERE，已删除应用不受影响。
     *  更新失败只影响列表排序，不阻塞对话本身，吞掉并记日志。 */
    private void touchAppUpdateTime(Long appId, LocalDateTime now) {
        try {
            App app = new App();
            app.setId(appId);
            app.setUpdateTime(now);
            appMapper.update(app);
        } catch (Exception e) {
            log.warn("刷新应用更新时间失败，appId={}", appId, e);
        }
    }

    /**
     * 分页执行查询，pageSize 超过 {@code sizeLimit} 时截断到 {@code sizeLimit}。
     * 管理员列表传 {@link Integer#MAX_VALUE} 即不限制。
     */
    private Page<ChatHistory> pageByRequest(PageRequest request,
                                            int sizeLimit, QueryWrapper queryWrapper) {
        int pageNum = request == null ? 1 : request.getPageNum();
        int pageSize = request == null ? 10 : request.getPageSize();
        if (pageSize <= 0) {
            pageSize = 10;
        }
        pageSize = Math.min(pageSize, sizeLimit);
        return this.page(new Page<>(pageNum, pageSize), queryWrapper);
    }

    /** 实体分页结果转成对外 VO 分页结果 */
    private Page<ChatHistoryVO> toVOPage(Page<ChatHistory> historyPage) {
        List<ChatHistoryVO> voList = historyPage.getRecords().stream()
                .map(this::toVO)
                .collect(Collectors.toList());
        Page<ChatHistoryVO> voPage = new Page<>(historyPage.getPageNumber(), historyPage.getPageSize(), historyPage.getTotalRow());
        voPage.setRecords(voList);
        return voPage;
    }

    private ChatHistoryVO toVO(ChatHistory chatHistory) {
        ChatHistoryVO chatHistoryVO = new ChatHistoryVO();
        chatHistoryVO.setId(chatHistory.getId());
        chatHistoryVO.setAppId(chatHistory.getAppId());
        chatHistoryVO.setUserId(chatHistory.getUserId());
        chatHistoryVO.setMessageType(chatHistory.getMessageType());
        chatHistoryVO.setMessage(chatHistory.getMessage());
        chatHistoryVO.setCreateTime(chatHistory.getCreateTime());
        chatHistoryVO.setUpdateTime(chatHistory.getUpdateTime());
        return chatHistoryVO;
    }

}
