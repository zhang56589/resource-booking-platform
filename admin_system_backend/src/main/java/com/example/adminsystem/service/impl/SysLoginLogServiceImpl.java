package com.example.adminsystem.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.adminsystem.entity.SysLoginLog;
import com.example.adminsystem.mapper.SysLoginLogMapper;
import com.example.adminsystem.service.SysLoginLogService;
import org.springframework.stereotype.Service;

@Service
public class SysLoginLogServiceImpl
        extends ServiceImpl<SysLoginLogMapper, SysLoginLog>
        implements SysLoginLogService {
}
