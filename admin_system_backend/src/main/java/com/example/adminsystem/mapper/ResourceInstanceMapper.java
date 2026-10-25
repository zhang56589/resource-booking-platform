package com.example.adminsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.adminsystem.entity.ResourceInstance;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ResourceInstanceMapper extends BaseMapper<ResourceInstance> {

    @Select("SELECT * FROM resource_instance WHERE instance_code = #{instanceCode} AND deleted = 0 LIMIT 1")
    @Results(id = "resourceInstanceResult", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "type_id", property = "typeId"),
            @Result(column = "instance_code", property = "instanceCode"),
            @Result(column = "instance_name", property = "instanceName"),
            @Result(column = "capacity", property = "capacity"),
            @Result(column = "location", property = "location"),
            @Result(column = "attributes_json", property = "attributesJson"),
            @Result(column = "status", property = "status"),
            @Result(column = "deleted", property = "deleted"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    ResourceInstance selectByInstanceCode(@Param("instanceCode") String instanceCode);

    @Select("SELECT * FROM resource_instance WHERE instance_code = #{instanceCode} AND deleted = 0 LIMIT 1 FOR UPDATE")
    @Results(id = "resourceInstanceForUpdateResult", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "type_id", property = "typeId"),
            @Result(column = "instance_code", property = "instanceCode"),
            @Result(column = "instance_name", property = "instanceName"),
            @Result(column = "capacity", property = "capacity"),
            @Result(column = "location", property = "location"),
            @Result(column = "attributes_json", property = "attributesJson"),
            @Result(column = "status", property = "status"),
            @Result(column = "deleted", property = "deleted"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    ResourceInstance selectByInstanceCodeForUpdate(@Param("instanceCode") String instanceCode);

    @Select("SELECT * FROM resource_instance WHERE id = #{id} AND deleted = 0 LIMIT 1 FOR UPDATE")
    @Results(id = "resourceInstanceByIdForUpdateResult", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "type_id", property = "typeId"),
            @Result(column = "instance_code", property = "instanceCode"),
            @Result(column = "instance_name", property = "instanceName"),
            @Result(column = "capacity", property = "capacity"),
            @Result(column = "location", property = "location"),
            @Result(column = "attributes_json", property = "attributesJson"),
            @Result(column = "status", property = "status"),
            @Result(column = "deleted", property = "deleted"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    ResourceInstance selectByIdForUpdate(@Param("id") Long id);

    @Update("UPDATE resource_instance SET status = #{status}, update_time = NOW() "
            + "WHERE instance_code = #{instanceCode} AND deleted = 0")
    int updateStatusByCode(@Param("instanceCode") String instanceCode, @Param("status") Integer status);

    @Update("UPDATE resource_instance SET deleted = 1, update_time = NOW() "
            + "WHERE instance_code = #{instanceCode} AND deleted = 0")
    int softDeleteByCode(@Param("instanceCode") String instanceCode);
}
