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
 * 预约明细：时段类资源固定一条 SLOT，配额类可扩展 SEAT / QUOTA。
 */
@Data
@TableName("schedule_booking_item")
public class ScheduleBookingItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long bookingId;

    private String itemType;

    private String itemKey;

    private Integer quantity;

    private String unit;

    private String extJson;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
