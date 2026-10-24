package com.example.adminsystem.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 会议室批量操作 DTO：批量启用/禁用/删除共用入参。
 */
@Data
public class RoomBatchDTO {

    @NotEmpty(message = "ids 不能为空")
    private List<Long> ids;
}
