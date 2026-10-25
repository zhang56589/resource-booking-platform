package com.example.adminsystem.plugin;

/**
 * 资源调度规则插件。
 * 一个资源类型对应一个插件；新增资源类型只新增实现，不改内核。
 */
public interface ScheduleRulePlugin {

    /** 插件唯一编码，如 TIME_EXCLUSIVE */
    String pluginCode();

    /** 是否支持该资源类型 */
    boolean supports(String typeCode);

    /** 返回锁 key；返回空表示该动作不加分布式锁 */
    String buildLockKey(ScheduleContext ctx);

    /** 预约生效前的校验；失败抛 BusinessException */
    void validate(ScheduleContext ctx);

    /** 预约生效后的资源侧动作，事务内执行 */
    void onBooked(ScheduleContext ctx);

    /** 取消后的归还动作，事务内执行 */
    void onCancelled(ScheduleContext ctx);
}
