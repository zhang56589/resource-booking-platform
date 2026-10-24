package com.example.adminsystem.config;

import com.example.adminsystem.util.BusinessException;
import com.example.adminsystem.util.Result;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.sql.SQLException;
import java.util.Set;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 捕获业务层主动抛出的业务异常
     */
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<?> handleBusinessException(BusinessException e) {
        log.warn("业务异常：{}", e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    /**
     * 捕获 @Valid + @RequestBody JSON请求体校验异常
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<?> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String msg = (fieldError != null) ? fieldError.getDefaultMessage() : "参数校验失败";
        log.warn("JSON请求体参数校验异常：{}", msg);
        // #5 用 Result.BAD_REQUEST 常量替代裸写 400
        return Result.fail(Result.BAD_REQUEST, msg);
    }

    /**
     * 捕获 @Valid form-data 表单校验异常
     */
    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<?> handleBindException(BindException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String msg = (fieldError != null) ? fieldError.getDefaultMessage() : "参数校验失败";
        log.warn("参数校验异常：{}", msg);
        return Result.fail(Result.BAD_REQUEST, msg);
    }

    /**
     * 捕获 @RequestParam 简单参数校验
     */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<?> handleConstraintViolation(ConstraintViolationException e) {
        Set<ConstraintViolation<?>> violations = e.getConstraintViolations();
        String msg = violations.iterator().next().getMessage();
        log.warn("参数校验异常：{}", msg);
        return Result.fail(Result.BAD_REQUEST, msg);
    }

    /**
     * 捕获 404 接口不存在
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<?> handleNoResourceFound(NoResourceFoundException e) {
        log.warn("访问不存在接口：{}", e.getMessage());
        return Result.fail(Result.NOT_FOUND, "接口不存在");
    }

    /**
     * H-5: 捕获 MySQL 锁等待超时（FOR UPDATE 行锁被其他事务长时间持有）。
     * Spring 会把底层 MySQL LockWaitTimeoutException 包一层 TransactionSystemException。
     * 典型触发场景：上一个事务持锁超过 innodb_lock_wait_timeout（默认50s）未提交。
     * 返回业务化 400 提示，用户友好，避免看到 500 "服务器内部错误"。
     */
    @ExceptionHandler(TransactionSystemException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<?> handleTransactionSystem(TransactionSystemException e) {
        Throwable root = getRootCause(e);
        if (root instanceof SQLException sqlException && sqlException.getErrorCode() == 1205) {
            log.warn("数据库锁等待超时 errorCode=1205, msg={}", sqlException.getMessage());
            return Result.fail(Result.BAD_REQUEST, "系统繁忙，请稍后重试");
        }
        // 不是锁超时 → 兜底处理（理论上不会走到这里）
        log.error("事务系统异常（非锁超时）", e);
        return Result.fail(Result.SERVER_ERROR, "服务器内部错误，请联系管理员");
    }

    /**
     * 兜底捕获全部未知异常
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<?> handleAllException(Exception e) {
        log.error("系统发生未知异常", e);
        return Result.fail(Result.SERVER_ERROR, "服务器内部错误，请联系管理员");
    }

    /**
     * 递归获取异常链最深层根因。
     * TransactionSystemException → DataAccessException → SQLException 这样的嵌套链需要找到最底层。
     */
    private Throwable getRootCause(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
