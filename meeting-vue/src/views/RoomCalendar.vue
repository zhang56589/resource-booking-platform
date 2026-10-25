<template>
  <div class="page-container">
    <el-card>
      <template #header>
        <div class="cal-header">
          <span>预约日历</span>
          <div class="cal-tools">
            <el-radio-group v-model="viewMode" size="small">
              <el-radio-button :value="'month'">月视图</el-radio-button>
              <el-radio-button :value="'day'">日视图</el-radio-button>
            </el-radio-group>
            <el-date-picker
              v-model="currentMonth"
              type="month"
              placeholder="选择月份"
              format="YYYY-MM"
              value-format="YYYY-MM"
              :clearable="false"
              size="small"
              @change="uxOnMonthChange"
            />
          </div>
        </div>
      </template>

      <!-- 状态图例 -->
      <div class="cal-legend">
        <span class="legend-item" v-for="item in uxStatusLegend" :key="item.value">
          <i class="legend-dot" :style="{ backgroundColor: item.color }"></i>
          {{ item.label }}
        </span>
      </div>

      <!-- 月视图 -->
      <div v-show="viewMode === 'month'" class="cal-week-bar">
        <span v-for="w in uxWeekNames" :key="w">{{ w }}</span>
      </div>
      <div v-show="viewMode === 'month'" class="cal-grid" v-loading="loading">
        <div
          v-for="(cell, idx) in uxMonthCells"
          :key="idx"
          class="cal-cell"
          :class="{ 'cal-cell--empty': cell.empty, 'cal-cell--today': cell.isToday }"
        >
          <template v-if="!cell.empty">
            <div class="cal-cell-day">{{ cell.day }}</div>
            <div
              v-for="b in cell.bookings"
              :key="b.id"
              class="cal-block"
              :style="{ backgroundColor: uxStatusColor(b.resStatus) }"
              :title="`${b.roomName} ${b.startTime}~${b.endTime}`"
              @click="uxOpenDetail(b)"
            >
              <span class="cal-block-time">{{ uxTimeText(b) }}</span>
              <span class="cal-block-room">{{ b.roomName }}</span>
            </div>
          </template>
        </div>
      </div>

      <!-- 日视图 -->
      <div v-show="viewMode === 'day'" v-loading="loading">
        <div class="day-picker">
          <el-date-picker
            v-model="selectedDate"
            type="date"
            placeholder="选择日期"
            format="YYYY-MM-DD"
            value-format="YYYY-MM-DD"
            :clearable="false"
            size="small"
          />
        </div>
        <el-table :data="uxDayBookings" border style="width: 100%">
          <el-table-column prop="roomName" label="会议室" min-width="140" />
          <el-table-column prop="title" label="会议主题" min-width="140" />
          <el-table-column label="时间段" min-width="200">
            <template #default="{ row }">
              {{ uxTimeRangeText(row) }}
            </template>
          </el-table-column>
          <el-table-column prop="username" label="申请人" width="120" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="uxStatusTagType(row.resStatus)">
                {{ uxStatusText(row.resStatus) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="100" fixed="right">
            <template #default="{ row }">
              <el-button type="primary" link @click="uxOpenDetail(row)">详情</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-card>

    <!-- 预约详情弹窗：字段全部来自 BookingVO，未新增后端请求 -->
    <el-dialog v-model="detailVisible" title="预约详情" width="480px">
      <el-descriptions :column="1" border v-if="detailRow">
        <el-descriptions-item label="预约ID">{{ detailRow.id }}</el-descriptions-item>
        <el-descriptions-item label="会议室">{{ detailRow.roomName }}</el-descriptions-item>
        <el-descriptions-item label="申请人">{{ detailRow.username || '-' }}</el-descriptions-item>
        <el-descriptions-item label="会议主题">{{ detailRow.title || '-' }}</el-descriptions-item>
        <el-descriptions-item label="开始时间">{{ detailRow.startTime }}</el-descriptions-item>
        <el-descriptions-item label="结束时间">{{ detailRow.endTime }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="uxStatusTagType(detailRow.resStatus)">
            {{ uxStatusText(detailRow.resStatus) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="申请事由">{{ detailRow.reason || '-' }}</el-descriptions-item>
        <el-descriptions-item label="驳回原因">{{ detailRow.rejectReason || '-' }}</el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { getAdminBookingPage, getUserBookingPage } from '@/api/booking'

// 当前登录角色（读取既有 localStorage，不改动原有登录逻辑）
const role = computed(() => Number(localStorage.getItem('role')))

const loading = ref(false)
const viewMode = ref('month')
const currentMonth = ref(uxMonthString(new Date()))
const selectedDate = ref(uxDateString(new Date()))
const bookingList = ref([])

const detailVisible = ref(false)
const detailRow = ref(null)

const uxWeekNames = ['一', '二', '三', '四', '五', '六', '日']
// 状态标准色：待审批橙 / 已通过绿 / 已驳回红 / 已取消灰（与全局规范一致）
const uxStatusLegend = [
  { value: 0, label: '待审批', color: '#e6a23c' },
  { value: 1, label: '已通过', color: '#2ba471' },
  { value: 2, label: '已驳回', color: '#e04c4c' },
  { value: 3, label: '已取消', color: '#909399' }
]

function uxPad(n) {
  return String(n).padStart(2, '0')
}
function uxDateString(d) {
  return `${d.getFullYear()}-${uxPad(d.getMonth() + 1)}-${uxPad(d.getDate())}`
}
function uxMonthString(d) {
  return `${d.getFullYear()}-${uxPad(d.getMonth() + 1)}`
}
function uxFormatDateTime(d) {
  return `${uxDateString(d)} ${uxPad(d.getHours())}:${uxPad(d.getMinutes())}:${uxPad(
    d.getSeconds()
  )}`
}

const uxStatusColor = (s) =>
  ({ 0: '#e6a23c', 1: '#2ba471', 2: '#e04c4c', 3: '#909399' })[s] || '#909399'
const uxStatusText = (s) =>
  ({ 0: '待审批', 1: '已通过', 2: '已驳回', 3: '已取消' })[s] || '未知'
const uxStatusTagType = (s) =>
  ({ 0: 'warning', 1: 'success', 2: 'danger', 3: 'info' })[s] || 'info'

const uxTimeText = (b) => (b.startTime ? String(b.startTime).slice(11, 16) : '')
const uxTimeRangeText = (b) => `${b.startTime} ~ ${b.endTime}`
const uxBookingStartDate = (b) => String(b.startTime).slice(0, 10)
const uxBookingEndDate = (b) => String(b.endTime).slice(0, 10)

// 月份解析：currentMonth 为 'YYYY-MM'
const uxMonthDate = computed(() => {
  const [y, m] = String(currentMonth.value).split('-')
  return new Date(Number(y), Number(m) - 1, 1)
})

const uxOnMonthChange = () => {
  const d = uxMonthDate.value
  selectedDate.value = uxDateString(d)
  uxFetch()
}

// 按日期取当天占用的预约（跨天预约会出现在其覆盖的每一天）
const uxBookingsOnDate = (dateStr) =>
  bookingList.value.filter(
    (b) => uxBookingStartDate(b) <= dateStr && uxBookingEndDate(b) >= dateStr
  )

const uxMonthCells = computed(() => {
  const y = uxMonthDate.value.getFullYear()
  const m = uxMonthDate.value.getMonth()
  const firstWeekday = (new Date(y, m, 1).getDay() + 6) % 7 // 周一为第一天
  const daysInMonth = new Date(y, m + 1, 0).getDate()
  const todayStr = uxDateString(new Date())
  const cells = []
  for (let i = 0; i < firstWeekday; i++) {
    cells.push({ empty: true })
  }
  for (let d = 1; d <= daysInMonth; d++) {
    const dateStr = `${y}-${uxPad(m + 1)}-${uxPad(d)}`
    cells.push({
      empty: false,
      day: d,
      dateStr,
      isToday: dateStr === todayStr,
      bookings: uxBookingsOnDate(dateStr)
    })
  }
  return cells
})

const uxDayBookings = computed(() =>
  uxBookingsOnDate(selectedDate.value).slice().sort((a, b) =>
    String(a.startTime).localeCompare(String(b.startTime))
  )
)

const uxOpenDetail = (row) => {
  detailRow.value = row
  detailVisible.value = true
}

// 查询范围：复用后端已有的时间段筛选参数（startTime/endTime，格式 yyyy-MM-dd HH:mm:ss）
// 后端筛选语义是「完全落在区间内」（start_time >= ? AND end_time <= ?），
// 所以前后各扩 1 天，避免跨月/跨天预约两个月都查不到；展示仍由 uxBookingsOnDate 按天过滤
// 后端 pageSize 上限 100，单页取会导致超过 100 条的月份静默丢数据，这里分页拉全量
const uxFetch = async () => {
  loading.value = true
  try {
    const y = uxMonthDate.value.getFullYear()
    const m = uxMonthDate.value.getMonth()
    const start = new Date(y, m, 0, 0, 0, 0)
    const end = new Date(y, m + 1, 1, 23, 59, 59)
    const pageApi = role.value === 1 ? getAdminBookingPage : getUserBookingPage
    const all = []
    let pageNum = 1
    while (true) {
      const res = await pageApi({
        pageNum,
        pageSize: 100,
        startTime: uxFormatDateTime(start),
        endTime: uxFormatDateTime(end)
      })
      const records = res.data.records || []
      all.push(...records)
      const total = res.data.total || 0
      // 取满 total / 空页 / 兜底 50 页（5000 条）即停止
      if (!records.length || all.length >= total || pageNum >= 50) break
      pageNum += 1
    }
    bookingList.value = all
  } finally {
    loading.value = false
  }
}

// 日视图可选任意日期，但数据只按 currentMonth 拉取；
// 切到其他月份时同步月份并重新查询，否则日视图会误显示为空
watch(selectedDate, (val) => {
  if (!val) return
  const month = String(val).slice(0, 7)
  if (month && month !== currentMonth.value) {
    currentMonth.value = month
    uxFetch()
  }
})

onMounted(uxFetch)
</script>

<style scoped>
/* 本次升级新增样式 */
.cal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.cal-tools {
  display: flex;
  align-items: center;
  gap: 12px;
}

.cal-legend {
  display: flex;
  gap: 16px;
  margin-bottom: 12px;
  font-size: 13px;
  color: #4e5969;
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.legend-dot {
  width: 10px;
  height: 10px;
  border-radius: 3px;
  display: inline-block;
}

.cal-week-bar {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  text-align: center;
  font-size: 13px;
  color: #86909c;
  padding: 6px 0;
  border-bottom: 1px solid #e5e6eb;
}

.cal-grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  border-left: 1px solid #e5e6eb;
}

.cal-cell {
  min-height: 96px;
  border-right: 1px solid #e5e6eb;
  border-bottom: 1px solid #e5e6eb;
  padding: 6px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.cal-cell--empty {
  background-color: #fafbfc;
}

.cal-cell--today {
  background-color: #eef4fe;
}

.cal-cell-day {
  font-size: 12px;
  color: #4e5969;
  font-weight: 600;
}

.cal-block {
  color: #fff;
  border-radius: 4px;
  padding: 3px 6px;
  font-size: 12px;
  line-height: 1.4;
  cursor: pointer;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.cal-block-time {
  font-weight: 600;
  margin-right: 4px;
}

.day-picker {
  margin-bottom: 12px;
}
</style>
