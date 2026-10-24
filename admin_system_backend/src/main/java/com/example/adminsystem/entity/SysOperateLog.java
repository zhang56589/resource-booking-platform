package com.example.adminsystem.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_operate_log")
public class SysOperateLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作人id */
    @TableField("operator_id")
    private Long operatorId;

    /** 操作人用户名 */
    @TableField("operator_name")
    private String operatorName;

    /** 目标用户id */
    @TableField("target_id")
    private Long targetId;

    /** 操作类型 */
    @TableField("operate_type")
    private String operateType;

    /** 操作IP */
    private String ip;

    /** 操作时间 */
    @TableField(value = "operate_time", fill = FieldFill.INSERT)
    private LocalDateTime operateTime;
}
