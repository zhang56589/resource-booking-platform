package com.example.adminsystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 角色表。
 * id 与 SysUser.role(Integer) 对齐：0=employee, 1=admin。
 * 后续扩展 auditor(2) 时，只需 INSERT 新角色 + sys_role_permission 映射即可。
 */
@Data
@TableName("sys_role")
public class SysRole {

    /** 与 SysUser.role 对齐：0=employee, 1=admin */
    @TableId(type = IdType.INPUT)
    private Integer id;

    /** 角色编码：employee / admin */
    private String code;

    /** 角色显示名 */
    private String name;

    /** 角色描述 */
    private String description;

    private LocalDateTime createTime;
}
