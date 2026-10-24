package com.example.adminsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.adminsystem.entity.SysMenu;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SysMenuMapper extends BaseMapper<SysMenu> {

    /** 按角色ID联查该角色可见的菜单（sys_role_menu 中间表），按 order_num 升序 */
    List<SysMenu> selectMenusByRoleId(@Param("roleId") Integer roleId);
}
