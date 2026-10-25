package com.example.adminsystem.plugin;

import com.example.adminsystem.mapper.ResourceTypeMapper;
import com.example.adminsystem.util.BusinessException;
import com.example.adminsystem.util.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 插件注册表：启动期收集所有 ScheduleRulePlugin，运行期按资源类型路由。
 * 使用 ObjectProvider 注入，保证没有插件实现时也能正常启动。
 *
 * 路由顺序：
 *   1. 插件自身 supports(typeCode) 快路径（插件显式声明支持的类型）
 *   2. 未命中则回查 resource_type.plugin_code，按插件编码路由（新增同规则资源类型只需 INSERT 字典）
 */
@Slf4j
@Component
public class PluginRegistry {

    private final Map<String, ScheduleRulePlugin> pluginMap = new LinkedHashMap<>();
    private final ResourceTypeMapper resourceTypeMapper;

    public PluginRegistry(ObjectProvider<ScheduleRulePlugin> pluginProvider,
                          ResourceTypeMapper resourceTypeMapper) {
        this.resourceTypeMapper = resourceTypeMapper;
        pluginProvider.orderedStream().forEach(this::register);
        log.info("调度插件注册完成 count={}, codes={}", pluginMap.size(), pluginMap.keySet());
    }

    public ScheduleRulePlugin resolveByTypeCode(String typeCode) {
        if (typeCode == null || typeCode.isBlank()) {
            throw new BusinessException(Result.BAD_REQUEST, "不支持的资源类型");
        }
        ScheduleRulePlugin direct = pluginMap.values().stream()
                .filter(plugin -> plugin.supports(typeCode))
                .findFirst()
                .orElse(null);
        if (direct != null) {
            return direct;
        }
        // 二级路由：资源类型字典里配置的 plugin_code
        String pluginCode = resourceTypeMapper.selectPluginCodeByTypeCode(typeCode);
        ScheduleRulePlugin byCode = pluginCode == null ? null : pluginMap.get(pluginCode);
        if (byCode != null) {
            log.info("插件二级路由 typeCode={}, pluginCode={}", typeCode, pluginCode);
            return byCode;
        }
        throw new BusinessException(Result.BAD_REQUEST, "不支持的资源类型");
    }

    private void register(ScheduleRulePlugin plugin) {
        String code = plugin.pluginCode();
        if (code == null || code.isBlank()) {
            throw new IllegalStateException("调度插件编码不能为空");
        }
        if (pluginMap.putIfAbsent(code, plugin) != null) {
            throw new IllegalStateException("调度插件编码重复: " + code);
        }
    }
}
