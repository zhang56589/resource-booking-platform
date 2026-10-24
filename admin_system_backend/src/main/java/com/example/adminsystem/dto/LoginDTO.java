package com.example.adminsystem.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 登录入参 DTO —— 统一使用 @Valid 校验，防止 username/password 为 null 或空串透传到 Service。
 */
@Data
public class LoginDTO {

    @NotBlank(message = "账号不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;
}
