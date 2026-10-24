package com.example.adminsystem.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.adminsystem.entity.SysOutbox;
import com.example.adminsystem.mapper.SysOutboxMapper;
import com.example.adminsystem.service.SysOutboxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 本地消息表 Service 实现。
 * 所有 mark* 方法是独立小事务（只 update 一行，快速提交），避免影响调用方业务事务边界。
 */
@Slf4j
@Service
public class SysOutboxServiceImpl extends ServiceImpl<SysOutboxMapper, SysOutbox>
        implements SysOutboxService {

    private static final int DEFAULT_BATCH = 100;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int markProcessing(int limit) {
        if (limit <= 0) limit = DEFAULT_BATCH;
        int affected = baseMapper.markProcessing(limit);
        log.debug("Outbox 抢占待发送批次 affected={}", affected);
        return affected;
    }

    @Override
    public List<SysOutbox> selectProcessing() {
        return baseMapper.selectProcessing();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int markSent(Long id) {
        return baseMapper.markSent(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int markFailed(Long id) {
        return baseMapper.markFailed(id);
    }
}
