package com.example.adminsystem.service;

import com.example.adminsystem.plugin.ScheduleContext;
import com.example.adminsystem.plugin.ScheduleRulePlugin;

/**
 * 调度事务边界：由 ScheduleManager 在锁内调用，保证锁包事务。
 */
public interface ScheduleTxService {

    void book(ScheduleContext ctx, ScheduleRulePlugin plugin);

    void approve(ScheduleContext ctx, ScheduleRulePlugin plugin);

    void cancel(ScheduleContext ctx, ScheduleRulePlugin plugin);
}
