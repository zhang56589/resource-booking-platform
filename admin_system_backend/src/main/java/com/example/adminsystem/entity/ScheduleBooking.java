package com.example.adminsystem.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 统一预约主表（资源调度内核）。
 * status 列沿用 0待审批/1已通过/2已驳回/3已取消，实体属性名保持 resStatus 以兼容调用方。
 * title / reason / rejectReason 存放在 ext_json，避免为会议室单类型加列。
 */
@Data
@TableName("schedule_booking")
public class ScheduleBooking {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String bookingNo;

    private Long resourceTypeId;

    private Long resourceInstanceId;

    private Long userId;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Integer quantity;

    @TableField("status")
    private Integer resStatus;

    private String pluginCode;

    private String source;

    private String extJson;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
