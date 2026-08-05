package com.cg.yangaicodemother.common;

import com.cg.yangaicodemother.exception.ErrorCode;

public class ResultUtils {

    /**
     * 成功
     *
     * @param data 返回数据
     * @param <T>  数据类型
     * @return 统一响应
     */
    public static <T> BaseResponse<T> success(T data) {
        return new BaseResponse<>(0, data, "ok");
    }

    /**
     * 失败
     *
     * @param errorCode 错误码枚举
     * @return 统一响应
     */
    public static BaseResponse<?> error(ErrorCode errorCode) {
        return new BaseResponse<>(errorCode);
    }

    /**
     * 失败
     *
     * @param code    状态码
     * @param message 提示信息
     * @return 统一响应
     */
    public static BaseResponse<?> error(int code, String message) {
        return new BaseResponse<>(code, null, message);
    }

    /**
     * 失败（自定义提示消息）
     *
     * @param errorCode 错误码枚举
     * @param message   自定义提示
     * @return 统一响应
     */
    public static BaseResponse<?> error(ErrorCode errorCode, String message) {
        return new BaseResponse<>(errorCode.getCode(), null, message);
    }

}