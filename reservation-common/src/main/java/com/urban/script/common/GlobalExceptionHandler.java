package com.urban.script.common;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理器
 * <p>
 * 仅在引入 spring-boot-starter-web 的业务服务里生效（Gateway 用的是 WebFlux，
 * 其全局异常处理需自行实现 ErrorWebExceptionHandler，不受此类影响）。
 * </p>
 * <p>
 * 处理优先级：
 *   ① BusinessException           —— 业务层主动抛出，按其 code/message 原样返回
 *   ② MethodArgumentNotValidException —— @Valid / @Validated 参数校验失败
 *   ③ ConstraintViolationException —— 方法参数 @Validated 校验（非 DTO）
 *   ④ Exception                   —— 兜底：系统异常，统一返回 500
 * </p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ===================== 业务异常 =====================
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<R<Void>> handleBusiness(BusinessException e) {
        log.warn("[BusinessException] code={}, msg={}", e.getCode(), e.getMessage());
        return ResponseEntity
                .status(HttpStatus.OK)              // HTTP 200，业务层看 code 字段
                .body(R.fail(e.getCode(), e.getMessage()));
    }

    // ===================== @Valid / @Validated DTO 校验 =====================
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Void>> handleValid(MethodArgumentNotValidException e) {
        // 取第一个字段的错误信息返回给前端（也可以收集所有错误）
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("[MethodArgumentNotValidException] {}", detail);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(R.fail(ResultCode.PARAM_ERROR.getCode(),
                        detail == null ? ResultCode.PARAM_ERROR.getMsg() : detail));
    }

    // ===================== @Validated 方法参数校验（非 DTO） =====================
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<R<Void>> handleConstraint(ConstraintViolationException e) {
        log.warn("[ConstraintViolationException] {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(R.fail(ResultCode.PARAM_ERROR.getCode(), e.getMessage()));
    }

    // ===================== 兜底异常 =====================
    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleOther(Exception e) {
        // 系统异常必须打 ERROR 日志，方便排查
        log.error("[SystemException] uncaught exception:", e);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(R.fail(ResultCode.SYSTEM_ERROR.getCode(),
                        ResultCode.SYSTEM_ERROR.getMsg() + ": " + e.getMessage()));
    }
}
