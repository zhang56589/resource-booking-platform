package com.example.adminsystem.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UserPasswordDTO {
    @NotNull(message = "用户id不能为空")
    private Long id;

    @NotBlank(message = "新密码不能为空")
    private String password;
}
