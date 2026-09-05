package com.urban.script.common;

import lombok.Getter;

/**
 * 业务异常
 * <p>
 * 业务层遇到"可预期的错误"（如库存不足、用户不存在、状态不允许操作）时抛出此异常，
 * 由 {@link GlobalExceptionHandler} 统一拦截并转换为 R.fail JSON 响应。
 * </p>
 * <p>
 * 与 RuntimeException 不同，BusinessException 携带业务码 code，
 * 方便前端根据不同 code 做差异化提示或跳转（如 401 跳登录页）。
 * </p>
 */
@Getter
public class BusinessException extends RuntimeException {

    /** 业务码（默认 500 = BUSINES_ERROR） */
    private final int code;

    /** 业务异常消息（供 GlobalExceptionHandler 回写 R.message） */
    private final String message;

    public BusinessException(String message) {
        this(ResultCode.BUSINESS_ERROR.getCode(), message);
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
        this.message = message;
    }

    public BusinessException(ResultCode resultCode) {
        this(resultCode.getCode(), resultCode.getMsg());
    }

    public BusinessException(ResultCode resultCode, String message) {
        this(resultCode.getCode(), message);
    }
}
