package com.example.adminsystem.util;

import lombok.Data;

/**
 * 统一响应封装。
 * 状态码常量集中声明，Controller / Interceptor / GlobalExceptionHandler 共用。
 * —— 各层可按需逐步把硬编码 200/400/401 替换为常量，不强制一次改完。
 */
@Data
public class Result<T> {

    // ====== P2-7：状态码常量 ======
    public static final int SUCCESS = 200;
    public static final int BAD_REQUEST = 400;
    public static final int UNAUTHORIZED = 401;
    public static final int FORBIDDEN = 403;
    public static final int NOT_FOUND = 404;
    public static final int SERVER_ERROR = 500;

    private Integer code;
    private String msg;
    private T data;

    public static <T> Result<T> success() {
        Result<T> r = new Result<>();
        r.setCode(SUCCESS);
        r.setMsg("成功");
        return r;
    }

    public static <T> Result<T> success(T data) {
        Result<T> r = new Result<>();
        r.setCode(SUCCESS);
        r.setMsg("成功");
        r.setData(data);
        return r;
    }

    public static <T> Result<T> success(String msg) {
        Result<T> r = new Result<>();
        r.setCode(SUCCESS);
        r.setMsg(msg);
        return r;
    }

    public static <T> Result<T> fail(Integer code, String msg) {
        Result<T> r = new Result<>();
        r.setCode(code);
        r.setMsg(msg);
        return r;
    }
}
