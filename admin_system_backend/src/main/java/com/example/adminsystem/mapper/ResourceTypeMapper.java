package com.example.adminsystem.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ResourceTypeMapper {

    @Select("SELECT id FROM resource_type WHERE type_code = #{typeCode} AND deleted = 0 LIMIT 1")
    Long selectIdByTypeCode(@Param("typeCode") String typeCode);
}
