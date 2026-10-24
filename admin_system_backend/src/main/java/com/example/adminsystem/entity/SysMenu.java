package com.example.adminsystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** P1-5：动态菜单表 sys_menu */
@Data
@TableName("sys_menu")
public class SysMenu {
    @TableId(type = IdType.AUTO)
    private Integer id;

    /** 父菜单ID，0=顶级 */
    private Integer parentId;

    /** 菜单名称 */
    private String name;

    /** 路由路径 */
    private String path;

    /** 前端组件路径（views 相对，无扩展名） */
    private String component;

    /** Element Plus 图标名 */
    private String icon;

    /** 关联权限码，可空 */
    private String permission;

    /** 排序号，升序 */
    private Integer orderNum;
}
