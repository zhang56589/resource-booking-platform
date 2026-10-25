package com.example.adminsystem.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ResourceTypeMapper {

    @Select("SELECT id FROM resource_type WHERE type_code = #{typeCode} AND deleted = 0 LIMIT 1")
    Long selectIdByTypeCode(@Param("typeCode") String typeCode);

    /** 资源类型绑定的插件编码，插件 supports() 未命中时的二级路由依据 */
    @Select("SELECT plugin_code FROM resource_type WHERE type_code = #{typeCode} AND enabled = 1 AND deleted = 0 LIMIT 1")
    String selectPluginCodeByTypeCode(@Param("typeCode") String typeCode);

    /** 预约记录 → 资源类型编码，取消等按 bookingId 驱动的动作需要 */
    @Select("SELECT type_code FROM resource_type WHERE id = #{id} AND deleted = 0 LIMIT 1")
    String selectTypeCodeById(@Param("id") Long id);
}
