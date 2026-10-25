package com.example.adminsystem.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通用预约入参：不限资源类型，插件由 resourceTypeCode 路由。
 * 时段类资源 quantity 固定 1；配额类资源按申请量传值。
 */
@Data
public class ScheduleBookDTO {

    @NotBlank(message = "资源类型不能为空")
    private String resourceTypeCode;

    @NotNull(message = "资源实例id不能为空")
    private Long instanceId;

    @NotNull(message = "开始时间不能为空")
    private LocalDateTime startTime;

    @NotNull(message = "结束时间不能为空")
    private LocalDateTime endTime;

    private Integer quantity;

    private String title;

    private String reason;
}
