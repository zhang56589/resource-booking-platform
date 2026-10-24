package com.example.adminsystem.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 角色-权限 关联表（多对多中间表）。
 * 复合主键 (role_id, permission_id)，MP 不强制加 @TableId。
 */
@Data
@TableName("sys_role_permission")
public class SysRolePermission {

    @TableField("role_id")
    private Integer roleId;

    @TableField("permission_id")
    private Integer permissionId;
}
