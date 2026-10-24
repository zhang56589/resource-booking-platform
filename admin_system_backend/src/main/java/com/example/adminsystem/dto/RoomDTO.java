package com.example.adminsystem.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class RoomDTO {
    /** 修改时必须传，新增时不传 */
    private Long id;

    @NotBlank(message = "会议室名称不能为空")
    private String roomName;

    @NotNull(message = "容纳人数不能为空")
    @Min(value = 1, message = "容纳人数至少为1")
    private Integer capacity;

    private String location;

    private String description;
}
