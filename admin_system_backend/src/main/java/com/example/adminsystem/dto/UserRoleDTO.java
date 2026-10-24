package com.example.adminsystem.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UserRoleDTO {
    @NotNull(message = "用户id不能为空")
    private Long id;

    @NotNull(message = "角色参数不能为空")
    private Integer role;
}
