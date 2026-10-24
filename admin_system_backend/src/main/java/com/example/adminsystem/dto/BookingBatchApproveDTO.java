package com.example.adminsystem.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 批量审批 DTO：复用单次审批的 status / rejectReason 校验语义，
 * Service 层循环调用单次 approve 逻辑，任一失败整体回滚。
 */
@Data
public class BookingBatchApproveDTO {

    @NotEmpty(message = "ids 不能为空")
    private List<Long> bookingIds;

    @NotNull(message = "审批状态不能为空")
    private Integer status;

    private String rejectReason;
}
