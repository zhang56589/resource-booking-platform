<template>
  <div class="page-container">
    <!-- 统计卡片：数据来源全部复用后端已有 GET 查询接口，未新增任何后端请求 -->
    <el-row :gutter="16">
      <el-col v-for="card in statCards" :key="card.key" :span="card.span">
        <el-card class="stat-card" shadow="hover">
          <div class="stat-icon" :style="{ backgroundColor: card.color }">
            <el-icon><component :is="card.icon" /></el-icon>
          </div>
          <div class="stat-body">
            <div class="stat-value">{{ card.value }}</div>
            <div class="stat-label">{{ card.label }}</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 预约状态占比饼图 -->
    <el-card class="chart-card">
      <template #header>
        <span>预约状态占比</span>
      </template>
      <div ref="chartRef" class="chart-box" v-loading="loading"></div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { Odometer, OfficeBuilding, Calendar, Bell } from '@element-plus/icons-vue'
// ECharts 按需引入，避免增大打包体积
import * as echarts from 'echarts/core'
import { PieChart } from 'echarts/charts'
import { TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { getRoomPage, getAvailableRoomList } from '@/api/room'
import { getAdminBookingPage, getUserBookingPage } from '@/api/booking'

echarts.use([PieChart, TooltipComponent, LegendComponent, CanvasRenderer])

const loading = ref(false)
const chartRef = ref(null)
let chartInstance = null

// 当前登录角色：0 员工 / 1 管理员（读取既有 localStorage，不改动原有登录逻辑）
const role = computed(() => Number(localStorage.getItem('role')))

// 统计数据（新增，独立于原有业务变量）
const stat = reactive({
  roomsTotal: 0,
  roomsEnabled: 0,
  todayBookings: 0,
  pendingBookings: 0
})
const pieData = reactive([0, 0, 0, 0])

const uxPad = (n) => String(n).padStart(2, '0')
const uxFormatDateTime = (d) => {
  return `${d.getFullYear()}-${uxPad(d.getMonth() + 1)}-${uxPad(d.getDate())} ${uxPad(
    d.getHours()
  )}:${uxPad(d.getMinutes())}:${uxPad(d.getSeconds())}`
}

const statCards = computed(() => {
  const span = role.value === 1 ? 6 : 8
  if (role.value === 1) {
    return [
      { key: 'roomsTotal', span, label: '会议室总数', value: stat.roomsTotal, color: '#2f6fed', icon: OfficeBuilding },
      { key: 'roomsEnabled', span, label: '启用会议室', value: stat.roomsEnabled, color: '#2ba471', icon: Odometer },
      { key: 'todayBookings', span, label: '今日预约数量', value: stat.todayBookings, color: '#e6a23c', icon: Calendar },
      { key: 'pendingBookings', span, label: '待审批预约数量', value: stat.pendingBookings, color: '#e04c4c', icon: Bell }
    ]
  }
  return [
    { key: 'roomsEnabled', span, label: '可用会议室', value: stat.roomsEnabled, color: '#2ba471', icon: Odometer },
    { key: 'todayBookings', span, label: '今日我的预约', value: stat.todayBookings, color: '#e6a23c', icon: Calendar },
    { key: 'pendingBookings', span, label: '我的待审批', value: stat.pendingBookings, color: '#e04c4c', icon: Bell }
  ]
})

// 查询「今日」范围的起止时间（yyyy-MM-dd HH:mm:ss，与后端入参格式一致）
const uxTodayRange = () => {
  const start = new Date()
  start.setHours(0, 0, 0, 0)
  const end = new Date()
  end.setHours(23, 59, 59, 0)
  return { startTime: uxFormatDateTime(start), endTime: uxFormatDateTime(end) }
}

// 后端 /room/page 的 pageSize 上限为 100，单页统计会在会议室超过 100 个时少算
const uxCountEnabledRooms = async () => {
  let pageNum = 1
  let enabled = 0
  while (true) {
    const res = await getRoomPage({ pageNum, pageSize: 100 })
    const records = res.data.records || []
    enabled += records.filter((r) => r.status === 1).length
    const total = res.data.total || 0
    if (!records.length || pageNum * 100 >= total || pageNum >= 50) break
    pageNum += 1
  }
  return enabled
}

const uxLoadAdminStat = async () => {
  const today = uxTodayRange()
  const [totalRes, roomsEnabled, todayRes, pendingRes] = await Promise.all([
    getRoomPage({ pageNum: 1, pageSize: 1 }),
    uxCountEnabledRooms(),
    getAdminBookingPage({ pageNum: 1, pageSize: 1, ...today }),
    getAdminBookingPage({ pageNum: 1, pageSize: 1, resStatus: 0 })
  ])
  stat.roomsTotal = totalRes.data.total || 0
  stat.roomsEnabled = roomsEnabled
  stat.todayBookings = todayRes.data.total || 0
  stat.pendingBookings = pendingRes.data.total || 0
}

const uxLoadEmployeeStat = async () => {
  const today = uxTodayRange()
  const [roomsRes, todayRes, pendingRes] = await Promise.all([
    getAvailableRoomList(),
    getUserBookingPage({ pageNum: 1, pageSize: 1, ...today }),
    getUserBookingPage({ pageNum: 1, pageSize: 1, resStatus: 0 })
  ])
  // 员工无 /room/page 权限，此处「可用会议室」取启用中的会议室数量
  stat.roomsEnabled = (roomsRes.data || []).length
  stat.todayBookings = todayRes.data.total || 0
  stat.pendingBookings = pendingRes.data.total || 0
}

const uxLoadPie = async () => {
  const pageApi = role.value === 1 ? getAdminBookingPage : getUserBookingPage
  const results = await Promise.all(
    [0, 1, 2, 3].map((s) =>
      pageApi({ pageNum: 1, pageSize: 1, resStatus: s })
    )
  )
  results.forEach((res, i) => {
    pieData[i] = res.data.total || 0
  })
}

const uxRenderChart = () => {
  if (!chartRef.value) return
  if (!chartInstance) {
    chartInstance = echarts.init(chartRef.value)
  }
  chartInstance.setOption({
    tooltip: { trigger: 'item', formatter: '{b}：{c}（{d}%）' },
    legend: { bottom: 0 },
    series: [
      {
        name: '预约状态占比',
        type: 'pie',
        radius: ['42%', '66%'],
        center: ['50%', '46%'],
        avoidLabelOverlap: true,
        label: { formatter: '{b}\n{c}' },
        data: [
          { value: pieData[0], name: '待审批', itemStyle: { color: '#e6a23c' } },
          { value: pieData[1], name: '已通过', itemStyle: { color: '#2ba471' } },
          { value: pieData[2], name: '已驳回', itemStyle: { color: '#e04c4c' } },
          { value: pieData[3], name: '已取消', itemStyle: { color: '#909399' } }
        ]
      }
    ]
  })
}

const uxResize = () => {
  if (chartInstance) chartInstance.resize()
}

const uxLoad = async () => {
  loading.value = true
  try {
    if (role.value === 1) {
      await uxLoadAdminStat()
    } else {
      await uxLoadEmployeeStat()
    }
    await uxLoadPie()
    await nextTick()
    uxRenderChart()
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  uxLoad()
  window.addEventListener('resize', uxResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', uxResize)
  if (chartInstance) {
    chartInstance.dispose()
    chartInstance = null
  }
})
</script>

<style scoped>
/* 本次升级新增样式 */
.chart-card {
  margin-top: 16px;
}

.chart-box {
  width: 100%;
  height: 320px;
}

.stat-card :deep(.el-card__body) {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 20px;
}

.stat-icon {
  width: 48px;
  height: 48px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 22px;
  flex-shrink: 0;
}

.stat-value {
  font-size: 26px;
  font-weight: 700;
  color: #1f2329;
  line-height: 1.2;
}

.stat-label {
  margin-top: 4px;
  font-size: 13px;
  color: #86909c;
}
</style>
