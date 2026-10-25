<template>
  <div class="page-container">
    <el-card>
      <div class="table-toolbar">
        <span>我的预约</span>
        <div class="filter-bar">
          <el-select
            v-model="query.roomId"
            placeholder="全部会议室"
            clearable
            style="width: 170px"
            @change="handleSearch"
          >
            <el-option
              v-for="room in uxRoomOptions"
              :key="room.id"
              :label="room.roomName"
              :value="room.id"
            />
          </el-select>
          <el-date-picker
            v-model="uxDateRange"
            type="datetimerange"
            range-separator="至"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            format="YYYY-MM-DD HH:mm:ss"
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 380px"
            @change="uxOnDateChange"
          />
          <el-select
            v-model="query.resStatus"
            placeholder="全部状态"
            clearable
            style="width: 140px"
            @change="handleSearch"
          >
            <el-option
              v-for="item in statusOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
          <el-button type="primary" @click="handleSearch">查询</el-button>
        </div>
      </div>

      <el-table :data="tableData" border v-loading="loading" style="width: 100%">
        <el-table-column prop="id" label="预约ID" width="80" />
        <el-table-column prop="roomName" label="会议室名称" min-width="140" />
        <el-table-column prop="title" label="会议主题" min-width="140" />
        <el-table-column label="预约时间段" min-width="300">
          <template #default="{ row }">
            {{ row.startTime }} 至 {{ row.endTime }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.resStatus)">
              {{ statusText(row.resStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="rejectReason" label="驳回原因" min-width="140">
          <template #default="{ row }">{{ row.rejectReason || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.resStatus === 0"
              type="danger"
              link
              @click="handleCancel(row.id)"
            >
              取消预约
            </el-button>
            <el-button
              v-else-if="row.resStatus === 1"
              type="danger"
              link
              @click="handleCancel(row.id)"
            >
              取消预约
            </el-button>
            <span v-else>-</span>
            <el-button type="primary" link @click="uxOpenDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination">
        <el-pagination
          v-model:current-page="query.pageNum"
          v-model:page-size="query.pageSize"
          :page-sizes="[5, 10, 20]"
          :total="total"
          layout="total, sizes, prev, pager, next"
          @current-change="loadTable"
          @size-change="handleSizeChange"
        />
      </div>
    </el-card>

    <!-- 预约详情弹窗（新增）：字段全部来自既有 BookingVO，未新增后端请求 -->
    <el-dialog v-model="uxDetailVisible" title="预约详情" width="480px">
      <el-descriptions :column="1" border v-if="uxDetailRow">
        <el-descriptions-item label="预约ID">{{ uxDetailRow.id }}</el-descriptions-item>
        <el-descriptions-item label="会议室">{{ uxDetailRow.roomName }}</el-descriptions-item>
        <el-descriptions-item label="申请人">{{ uxDetailRow.username || '-' }}</el-descriptions-item>
        <el-descriptions-item label="会议主题">{{ uxDetailRow.title || '-' }}</el-descriptions-item>
        <el-descriptions-item label="开始时间">{{ uxDetailRow.startTime }}</el-descriptions-item>
        <el-descriptions-item label="结束时间">{{ uxDetailRow.endTime }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="statusTagType(uxDetailRow.resStatus)">
            {{ statusText(uxDetailRow.resStatus) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="申请事由">{{ uxDetailRow.reason || '-' }}</el-descriptions-item>
        <el-descriptions-item label="驳回原因">{{ uxDetailRow.rejectReason || '-' }}</el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="uxDetailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getUserBookingPage, cancelBooking } from '@/api/booking'
import { getAvailableRoomList } from '@/api/room'

const loading = ref(false)
const tableData = ref([])
const total = ref(0)

const query = ref({
  pageNum: 1,
  pageSize: 5,
  resStatus: null,
  roomId: null,
  startTime: null,
  endTime: null
})

const statusOptions = [
  { value: 0, label: '待审批' },
  { value: 1, label: '已通过' },
  { value: 2, label: '已驳回' },
  { value: 3, label: '已取消' }
]

const statusText = (s) =>
  ({ 0: '待审批', 1: '已通过', 2: '已驳回', 3: '已取消' })[s] || '未知'

// 说明：按「状态视觉标准化」要求统一预约状态 tag 配色
// 待审批0=橙(warning) / 已通过1=绿(success) / 已驳回2=红(danger) / 已取消3=灰(info)
const statusTagType = (s) =>
  ({ 0: 'warning', 1: 'success', 2: 'danger', 3: 'info' })[s] || 'info'

// ===== 本次升级「新增」：会议室下拉、起止日期筛选、详情弹窗 =====
const uxRoomOptions = ref([])
const uxDateRange = ref([])
const uxDetailVisible = ref(false)
const uxDetailRow = ref(null)

const uxLoadRooms = async () => {
  const res = await getAvailableRoomList()
  uxRoomOptions.value = res.data || []
}

const uxOnDateChange = (val) => {
  query.value.startTime = val && val.length === 2 ? val[0] : null
  query.value.endTime = val && val.length === 2 ? val[1] : null
  handleSearch()
}

const uxOpenDetail = (row) => {
  uxDetailRow.value = row
  uxDetailVisible.value = true
}

const loadTable = async () => {
  loading.value = true
  try {
    const res = await getUserBookingPage({ ...query.value })
    tableData.value = res.data.records || []
    total.value = res.data.total || 0
  } finally {
    loading.value = false
  }
}

const handleSearch = () => {
  query.value.pageNum = 1
  loadTable()
}

const handleSizeChange = () => {
  query.value.pageNum = 1
  loadTable()
}

const handleCancel = async (id) => {
  try {
    await ElMessageBox.confirm('确定要取消该预约吗？', '提示', { type: 'warning' })
  } catch (e) {
    return
  }
  try {
    await cancelBooking(id)
    ElMessage.success('取消成功')
    loadTable()
  } catch (err) {
    // 错误提示由响应拦截器统一处理
  }
}

onMounted(() => {
  loadTable()
  uxLoadRooms()
})
</script>

<style scoped>
.filter-bar {
  display: flex;
  gap: 8px;
}
</style>