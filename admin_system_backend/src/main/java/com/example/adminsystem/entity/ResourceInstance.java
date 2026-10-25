package com.example.adminsystem.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 资源实例：会议室、工位、GPU 池等统一抽象。
 * 会议室 attributes_json 保留 legacyRoomId，用于对外 roomId 映射。
 */
@Data
@TableName("resource_instance")
public class ResourceInstance {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long typeId;

    private String instanceCode;

    private String instanceName;

    private Integer capacity;

    private String location;

    private String attributesJson;

    /** 0=禁用 1=启用 */
    private Integer status;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
