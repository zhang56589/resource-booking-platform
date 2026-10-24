package com.example.adminsystem.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.adminsystem.entity.SysOperateLog;
import com.example.adminsystem.mapper.SysOperateLogMapper;
import com.example.adminsystem.service.SysOperateLogService;
import org.springframework.stereotype.Service;

@Service
public class SysOperateLogServiceImpl
        extends ServiceImpl<SysOperateLogMapper, SysOperateLog>
        implements SysOperateLogService {
}
