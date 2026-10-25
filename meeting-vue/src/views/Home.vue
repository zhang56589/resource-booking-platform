<template>
  <el-container class="layout">
    <el-aside :width="isCollapse ? '64px' : '220px'" class="aside">
      <div class="logo">
        <span v-if="isCollapse">会议</span>
        <span v-else>会议室预约系统</span>
      </div>
      <el-menu
        :default-active="activeMenu"
        router
        :collapse="isCollapse"
        :collapse-transition="false"
        background-color="#304156"
        text-color="#bfcbd9"
        active-text-color="#409eff"
      >
        <el-menu-item
          v-for="item in menus"
          :key="item.path"
          :index="item.path"
        >
          <el-icon><component :is="menuIcons[item.path]" /></el-icon>
          <span>{{ item.title }}</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="header-left">
          <el-icon class="collapse-btn" @click="toggleCollapse">
            <component :is="isCollapse ? Expand : Fold" />
          </el-icon>
          <el-breadcrumb class="breadcrumb" separator="/">
            <el-breadcrumb-item>{{ role === 1 ? '管理员' : '员工' }}</el-breadcrumb-item>
            <el-breadcrumb-item>
              <div class="header-title">{{ currentTitle }}</div>
            </el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div class="header-right">
          <span class="user-info">
            {{ username }}
            <el-tag size="small" :type="role === 1 ? 'danger' : 'success'">
              {{ role === 1 ? '管理员' : '员工' }}
            </el-tag>
          </span>
          <el-button link type="primary" @click="handleLogout">退出登录</el-button>
        </div>
      </el-header>

      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import {
  Fold,
  Expand,
  Odometer,
  Calendar,
  OfficeBuilding,
  Tickets,
  User,
  Document
} from '@element-plus/icons-vue'
import { ref } from 'vue'

const route = useRoute()
const router = useRouter()

const role = computed(() => Number(localStorage.getItem('role')))
const username = computed(() => localStorage.getItem('username') || '用户')

// P1-5：优先使用后端下发的动态菜单（登录时写入 localStorage.menus），
// 接口失败或未拉取时回退到静态菜单兜底，保证侧边栏永不空白
const fallbackMenus = computed(() =>
  role.value === 1
    ? [
        { path: '/home/dashboard', title: '工作台' },
        { path: '/home/calendar', title: '预约日历' },
        { path: '/home/room-manage', title: '会议室管理' },
        { path: '/home/booking-approve', title: '预约审批' },
        { path: '/home/users', title: '用户管理' },
        { path: '/home/logs', title: '操作日志' }
      ]
    : [
        { path: '/home/dashboard', title: '工作台' },
        { path: '/home/calendar', title: '预约日历' },
        { path: '/home/rooms', title: '会议室列表' },
        { path: '/home/my-bookings', title: '我的预约' }
      ]
)

// 以路由表 meta.role 为准，过滤当前角色无权限的菜单，
// 避免后端 sys_role_menu 映射错配时把员工菜单渲染给管理员（点击后被路由守卫拦截）
const allowedMenuPaths = computed(() => {
  const paths = new Set()
  router.getRoutes().forEach((r) => {
    if (!r.path.startsWith('/home/')) return
    if (r.meta && r.meta.role !== undefined && Number(r.meta.role) !== role.value) return
    paths.add(r.path)
  })
  return paths
})

const menus = computed(() => {
  try {
    const raw = localStorage.getItem('menus')
    if (raw) {
      const list = JSON.parse(raw)
      if (Array.isArray(list) && list.length > 0) {
        const visible = list
          .map((m) => ({ path: m.path, title: m.name }))
          .filter((m) => allowedMenuPaths.value.has(m.path))
        if (visible.length > 0) return visible
      }
    }
  } catch (e) {
    // 解析失败走兜底
  }
  return fallbackMenus.value
})

const activeMenu = computed(() => route.path)
const currentTitle = computed(() => route.meta.title || '')

// 以下为本次升级「新增」：侧边栏折叠状态 + 菜单图标映射
const isCollapse = ref(false)
const toggleCollapse = () => {
  isCollapse.value = !isCollapse.value
}
const menuIcons = {
  '/home/dashboard': Odometer,
  '/home/calendar': Calendar,
  '/home/rooms': OfficeBuilding,
  '/home/my-bookings': Tickets,
  '/home/room-manage': OfficeBuilding,
  '/home/booking-approve': Tickets,
  '/home/users': User,
  '/home/logs': Document
}

const handleLogout = async () => {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '提示', { type: 'warning' })
  } catch (e) {
    return
  }
  localStorage.removeItem('token')
  localStorage.removeItem('role')
  localStorage.removeItem('userId')
  localStorage.removeItem('username')
  // P1-5：退出时同步清理动态菜单与权限缓存
  localStorage.removeItem('menus')
  localStorage.removeItem('permissions')
  router.push('/login')
}
</script>

<style scoped>
.layout {
  height: 100vh;
}

.aside {
  background-color: #304156;
  overflow-x: hidden;
}

.logo {
  height: 60px;
  line-height: 60px;
  text-align: center;
  color: #fff;
  font-size: 16px;
  font-weight: 600;
}

.aside :deep(.el-menu) {
  border-right: none;
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  border-bottom: 1px solid #e6e6e6;
}

.header-title {
  font-size: 16px;
  font-weight: 600;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #606266;
}

.main {
  background: #f5f7fa;
}

/* 以下为本次升级「新增」样式 */
.aside {
  transition: width 0.2s ease;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 16px;
}

.collapse-btn {
  font-size: 18px;
  cursor: pointer;
  color: #606266;
}

.collapse-btn:hover {
  color: var(--app-color-primary);
}

.breadcrumb :deep(.el-breadcrumb__inner) {
  color: #86909c;
  font-weight: 500;
}

.breadcrumb :deep(.header-title) {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}
</style>