package com.example.adminsystem.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class BookingDTO {
    @NotNull(message = "会议室id不能为空")
    private Long roomId;

    @NotNull(message = "开始时间不能为空")
    private LocalDateTime startTime;

    @NotNull(message = "结束时间不能为空")
    private LocalDateTime endTime;

    /**
     * P2-5 修复：title 加 @NotBlank，Controller 层双保险，
     * 不再依赖 Service.submitBooking 里的手动 trim 校验。
     */
    @NotBlank(message = "会议主题不能为空")
    private String title;

    private String reason;
}
