package com.cg.yangaicodemother.ai.memory;

import com.cg.yangaicodemother.mapper.ChatHistoryMapper;
import com.cg.yangaicodemother.model.entity.ChatHistory;
import com.cg.yangaicodemother.model.enums.MessageTypeEnum;
import com.mybatisflex.core.query.QueryWrapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.JacksonChatMessageJsonCodec;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Redis 对话记忆(短缓存)。
 *
 * <p>实现 langchain4j 的 {@link ChatMemoryStore}，把每个应用的对话记忆存到 Redis：
 * key = {@code chat:memory:{appId}}，写入即刷新 TTL，到点自动删除（短缓存）。
 *
 * <p>MySQL {@code chat_history} 仍是持久主存储（前端历史页/管理页读它），本类只当 AI 上下文缓存。
 * 因此缓存未命中时（TTL 过期 / 服务重启），从 MySQL 重建最近对话回填 Redis，
 * 保证「一小时后自动删」不丢上下文——过期删掉的只是缓存，历史永远可从库重建。
 */
@Component
public class RedisChatMemoryStore implements ChatMemoryStore {

    private static final String KEY_PREFIX = "chat:memory:";

    /** 短缓存 TTL(秒)：配置 ttl-hours 换算，每次写入刷新 */
    private final int ttlSeconds;

    private final JedisPool jedisPool;

    /** langchain4j 官方 ChatMessage 序列化器（Jackson2），类型多态由它处理 */
    private final JacksonChatMessageJsonCodec codec = new JacksonChatMessageJsonCodec();

    /** 仅用于缓存未命中时从 MySQL 重建（直接依赖 Mapper，避免服务层循环依赖） */
    @Resource
    private ChatHistoryMapper chatHistoryMapper;

    public RedisChatMemoryStore(
            @Value("${chat.memory.redis.host:127.0.0.1}") String host,
            @Value("${chat.memory.redis.port:6379}") int port,
            @Value("${chat.memory.redis.password:}") String password,
            @Value("${chat.memory.redis.ttl-hours:1}") int ttlHours) {
        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMaxTotal(16);
        cfg.setMaxIdle(8);
        cfg.setMinIdle(1);
        // 连接/读超时 3000ms：与探针一致；太短会在 Windows Redis 首次命令握手时误报 Read timed out
        int timeoutMs = 3000;
        this.jedisPool = (password == null || password.isBlank())
                ? new JedisPool(cfg, host, port, timeoutMs)
                : new JedisPool(cfg, host, port, timeoutMs, password);
        this.ttlSeconds = ttlHours * 3600;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String json;
        try (Jedis jedis = jedisPool.getResource()) {
            json = jedis.get(key(memoryId));
        }
        if (json == null || json.isBlank()) {
            // 缓存未命中：从 MySQL 重建最近对话并回填 Redis（短缓存可重建的关键）
            List<ChatMessage> seeded = loadRecentFromDb(memoryId);
            updateMessages(memoryId, seeded);
            return seeded;
        }
        return codec.messagesFromJson(json);
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        try (Jedis jedis = jedisPool.getResource()) {
            // setex：写入即重置 TTL，活跃对话持续续期；不活跃则到期自动删除
            jedis.setex(key(memoryId), ttlSeconds, codec.messagesToJson(messages));
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(key(memoryId));
        }
    }

    /** 从 MySQL 读取最近 10 条对话（新→旧），翻成旧→新，过滤 error 类型，作为缓存种子 */
    private List<ChatMessage> loadRecentFromDb(Object memoryId) {
        Long appId = ((Number) memoryId).longValue();
        List<ChatHistory> rows = chatHistoryMapper.selectListByQuery(QueryWrapper.create()
                .where(ChatHistory::getAppId).eq(appId)
                .orderBy(ChatHistory::getCreateTime, false)
                .orderBy(ChatHistory::getId, false)
                .limit(10));
        Collections.reverse(rows);
        List<ChatMessage> messages = new ArrayList<>(rows.size());
        for (ChatHistory h : rows) {
            if (MessageTypeEnum.USER.getValue().equals(h.getMessageType())) {
                messages.add(UserMessage.from(h.getMessage()));
            } else if (MessageTypeEnum.AI.getValue().equals(h.getMessageType())) {
                messages.add(AiMessage.from(h.getMessage()));
            }
            // error 类型不进记忆
        }
        return messages;
    }

    private static String key(Object memoryId) {
        return KEY_PREFIX + memoryId;
    }
}
