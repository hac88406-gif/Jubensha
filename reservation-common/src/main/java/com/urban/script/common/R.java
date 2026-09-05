package com.urban.script.common;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应封装
 * <p>
 * 所有微服务对外接口都应返回此类型，便于 Gateway / 前端统一解析。
 * 成功时 code=200、data 承载业务数据；失败时 code!=200、message 给错误描述。
 * </p>
 *
 * @param <T> data 字段的泛型
 */
@Data
public class R<T> implements Serializable {

    /** 业务返回码（HTTP 风格：200=成功，401=未登录，500=业务异常 ...） */
    private int code;

    /** 提示信息 */
    private String message;

    /** 业务数据（成功时填充） */
    private T data;

    /** 时间戳（前端排查问题用） */
    private long timestamp;

    public R() {
        this.timestamp = System.currentTimeMillis();
    }

    public R(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    // ===================== 静态工厂方法 =====================

    /** 成功（无数据） */
    public static <T> R<T> ok() {
        return new R<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMsg(), null);
    }

    /** 成功（带数据） */
    public static <T> R<T> ok(T data) {
        return new R<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMsg(), data);
    }

    /** 成功（自定义消息 + 数据） */
    public static <T> R<T> ok(String message, T data) {
        return new R<>(ResultCode.SUCCESS.getCode(), message, data);
    }

    /** 失败（自定义 code + message） */
    public static <T> R<T> fail(int code, String message) {
        return new R<>(code, message, null);
    }

    /** 失败（使用枚举 ResultCode） */
    public static <T> R<T> fail(ResultCode resultCode) {
        return new R<>(resultCode.getCode(), resultCode.getMsg(), null);
    }

    /** 失败（枚举 + 自定义详细消息，便于把底层异常信息透出） */
    public static <T> R<T> fail(ResultCode resultCode, String detailMsg) {
        return new R<>(resultCode.getCode(), detailMsg, null);
    }
}
