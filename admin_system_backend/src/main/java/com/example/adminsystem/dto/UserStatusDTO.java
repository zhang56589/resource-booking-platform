package com.example.adminsystem.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UserStatusDTO {
    @NotNull(message = "用户id不能为空")
    private Long id;

    @NotNull(message = "启用状态参数不能为空")
    private Integer enabled;
}
