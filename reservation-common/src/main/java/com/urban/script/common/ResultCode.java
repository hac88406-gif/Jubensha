package com.urban.script.common;

/**
 * 统一返回码枚举
 * <p>
 * 约定：200 = 成功，4xx = 客户端/鉴权错误，5xx = 服务端业务错误
 * </p>
 */
public enum ResultCode {

    SUCCESS(200, "成功"),
    UNAUTHORIZED(401, "未登录或Token无效"),
    FORBIDDEN(403, "无访问权限"),
    NOT_FOUND(404, "资源不存在"),
    PARAM_ERROR(400, "参数校验失败"),
    BUSINESS_ERROR(500, "业务异常"),
    SYSTEM_ERROR(501, "系统内部错误");

    /** 业务码 */
    private final int code;
    /** 提示信息 */
    private final String msg;

    ResultCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public int getCode() {
        return code;
    }

    public String getMsg() {
        return msg;
    }
}
