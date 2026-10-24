package com.example.adminsystem.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("meeting_room")
public class MeetingRoom {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会议室名称 */
    private String roomName;

    /** 容纳人数 */
    private Integer capacity;

    /** 位置 */
    private String location;

    /** 描述 */
    private String description;

    /**
     * 会议室状态：0不可用，1可用
     * @see com.example.adminsystem.enums.RoomStatusEnum
     */
    @TableField("enabled")
    private Integer status = 1;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 逻辑删除 0=正常 1=已删 */
    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
