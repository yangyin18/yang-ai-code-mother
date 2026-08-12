package com.cg.yangaicodemother.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

/**
 * 对话消息类型枚举。
 *
 * <p>区分对话历史中每一条消息的发送方：用户消息 / AI 消息 / 错误消息。
 * AI 回复失败时也会落一条 {@code error} 消息，保证对话的完整性。</p>
 */
@Getter
public enum MessageTypeEnum {

    USER("用户消息", "user"),
    AI("AI 消息", "ai"),
    ERROR("错误消息", "error");

    private final String text;

    private final String value;

    MessageTypeEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 根据 value 获取枚举
     *
     * @param value 枚举值的value
     * @return 枚举值
     */
    public static MessageTypeEnum getEnumByValue(String value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (MessageTypeEnum anEnum : MessageTypeEnum.values()) {
            if (anEnum.value.equals(value)) {
                return anEnum;
            }
        }
        return null;
    }
}
