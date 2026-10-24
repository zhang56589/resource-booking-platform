package com.example.adminsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.adminsystem.entity.SysRolePermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SysRolePermissionMapper extends BaseMapper<SysRolePermission> {

    /**
     * 按角色 ID 查询该角色拥有的所有权限码。
     */
    List<String> selectPermissionCodesByRoleId(@Param("roleId") Integer roleId);
}
