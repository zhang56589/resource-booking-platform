package com.example.adminsystem.vo;

import lombok.Data;

import java.util.List;

/** P1-5：动态菜单树节点 VO */
@Data
public class MenuVO {
    private Integer id;
    private Integer parentId;
    private String name;
    private String path;
    private String component;
    private String icon;
    private String permission;
    private Integer orderNum;
    private List<MenuVO> children;
}
