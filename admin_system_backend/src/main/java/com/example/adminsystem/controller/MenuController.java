package com.example.adminsystem.controller;

import com.example.adminsystem.entity.SysMenu;
import com.example.adminsystem.mapper.SysMenuMapper;
import com.example.adminsystem.mapper.SysRolePermissionMapper;
import com.example.adminsystem.util.Result;
import com.example.adminsystem.vo.MenuVO;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * P1-5：动态菜单接口。
 * 按 TokenInterceptor 写入 request 的 role（Integer）联查 sys_role_menu 过滤菜单树，
 * 同时返回该角色的权限码数组供前端 v-permission 指令使用。
 */
@RestController
@RequestMapping("/api/user")
public class MenuController {

    @Resource
    private SysMenuMapper sysMenuMapper;

    @Resource
    private SysRolePermissionMapper sysRolePermissionMapper;

    @GetMapping("/menu")
    public Result<Map<String, Object>> menu(HttpServletRequest request) {
        Object roleObj = request.getAttribute("role");
        if (roleObj == null) {
            return Result.fail(Result.UNAUTHORIZED, "未登录，请携带token");
        }
        Integer roleId = Integer.parseInt(roleObj.toString());

        List<SysMenu> menus = sysMenuMapper.selectMenusByRoleId(roleId);
        List<String> permissions = sysRolePermissionMapper.selectPermissionCodesByRoleId(roleId);

        Map<String, Object> data = new HashMap<>();
        data.put("menus", buildTree(menus));
        data.put("permissions", permissions);
        return Result.success(data);
    }

    /** 平铺列表 → 树（parentId=0 为顶级，按 orderNum 已升序） */
    private List<MenuVO> buildTree(List<SysMenu> menus) {
        Map<Integer, MenuVO> nodeMap = new HashMap<>();
        for (SysMenu m : menus) {
            MenuVO vo = new MenuVO();
            vo.setId(m.getId());
            vo.setParentId(m.getParentId());
            vo.setName(m.getName());
            vo.setPath(m.getPath());
            vo.setComponent(m.getComponent());
            vo.setIcon(m.getIcon());
            vo.setPermission(m.getPermission());
            vo.setOrderNum(m.getOrderNum());
            vo.setChildren(new ArrayList<>());
            nodeMap.put(vo.getId(), vo);
        }
        List<MenuVO> roots = new ArrayList<>();
        for (MenuVO vo : nodeMap.values()) {
            if (vo.getParentId() != null && vo.getParentId() == 0) {
                roots.add(vo);
            } else {
                MenuVO parent = nodeMap.get(vo.getParentId());
                if (parent != null) {
                    parent.getChildren().add(vo);
                } else {
                    roots.add(vo); // 父节点不在当前角色可见范围内 → 兜底提升为顶级
                }
            }
        }
        return roots;
    }
}
