package com.example.adminsystem.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_login_log")
public class SysLoginLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录用户名 */
    private String username;

    /** 登录IP */
    private String ip;

    /** 登录状态：0成功 1失败 */
    private Integer status;

    /** 备注（失败原因 / 成功摘要） */
    private String remark;

    /** 登录时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime loginTime;
}
