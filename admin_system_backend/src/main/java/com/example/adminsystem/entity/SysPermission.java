package com.example.adminsystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 权限点表。
 * code 格式统一为 "模块:动作"，例如 booking:approve / room:write / user:write。
 */
@Data
@TableName("sys_permission")
public class SysPermission {

    @TableId(type = IdType.AUTO)
    private Integer id;

    /** 权限码：模块:动作，如 booking:approve */
    private String code;

    /** 权限显示名 */
    private String name;

    private LocalDateTime createTime;
}
