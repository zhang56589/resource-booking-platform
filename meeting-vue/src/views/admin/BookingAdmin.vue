<template>
  <div class="page-container">
    <el-card>
      <div class="table-toolbar">
        <span>预约审批</span>
        <div class="filter-bar">
          <el-select
            v-model="query.roomId"
            placeholder="全部会议室"
            clearable
            style="width: 180px"
            @change="handleSearch"
          >
            <el-option
              v-for="room in roomOptions"
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
          <el-button
            v-permission="'booking:approve'"
            type="success"
            :loading="uxBatchLoading"
            @click="uxBatchApprove"
          >
            批量通过
          </el-button>
        </div>
      </div>

      <el-table
        :data="tableData"
        border
        v-loading="loading"
        style="width: 100%"
        @selection-change="uxOnSelectionChange"
      >
        <el-table-column type="selection" width="46" />
        <el-table-column prop="id" label="预约ID" width="80" />
        <el-table-column prop="roomName" label="会议室名称" min-width="140" />
        <el-table-column prop="username" label="申请人" width="120" />
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
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <template v-if="row.resStatus === 0">
              <el-button v-permission="'booking:approve'" type="success" link @click="handlePass(row)">通过</el-button>
              <el-button v-permission="'booking:approve'" type="danger" link @click="openRejectDialog(row)">
                驳回
              </el-button>
            </template>
            <span v-else>-</span>
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

    <!-- 驳回弹窗 -->
    <el-dialog v-model="rejectDialogVisible" title="填写驳回原因" width="460px">
      <el-form ref="rejectFormRef" :model="rejectForm" :rules="rejectRules">
        <el-form-item prop="rejectReason">
          <el-input
            v-model="rejectForm.rejectReason"
            type="textarea"
            :rows="4"
            placeholder="请输入驳回原因"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rejectDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitReject">
          确认驳回
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getAdminBookingPage, approveBooking, batchApproveBooking } from '@/api/booking'
import { getAvailableRoomList } from '@/api/room'

const loading = ref(false)
const submitting = ref(false)
const tableData = ref([])
const total = ref(0)
const roomOptions = ref([])

const query = ref({
  pageNum: 1,
  pageSize: 5,
  roomId: null,
  resStatus: null,
  startTime: null,
  endTime: null
})

const rejectDialogVisible = ref(false)
const rejectFormRef = ref(null)
const rejectForm = ref({ bookingId: null, rejectReason: '' })

const statusOptions = [
  { value: 0, label: '待审批' },
  { value: 1, label: '已通过' },
  { value: 2, label: '已驳回' },
  { value: 3, label: '已取消' }
]

const rejectRules = {
  rejectReason: [{ required: true, message: '请填写驳回原因', trigger: 'blur' }]
}

const statusText = (s) =>
  ({ 0: '待审批', 1: '已通过', 2: '已驳回', 3: '已取消' })[s] || '未知'

// 说明：按「状态视觉标准化」要求统一预约状态 tag 配色
// 待审批0=橙(warning) / 已通过1=绿(success) / 已驳回2=红(danger) / 已取消3=灰(info)
const statusTagType = (s) =>
  ({ 0: 'warning', 1: 'success', 2: 'danger', 3: 'info' })[s] || 'info'

// ===== 本次升级「新增」：起止日期筛选 + 批量审批（复用已有单条 approve 接口并发调用）=====
const uxDateRange = ref([])
const uxSelectedRows = ref([])
const uxBatchLoading = ref(false)

const uxOnDateChange = (val) => {
  query.value.startTime = val && val.length === 2 ? val[0] : null
  query.value.endTime = val && val.length === 2 ? val[1] : null
  handleSearch()
}

const uxOnSelectionChange = (rows) => {
  uxSelectedRows.value = rows
}

const uxBatchApprove = async () => {
  const pending = uxSelectedRows.value.filter((r) => r.resStatus === 0)
  if (!pending.length) {
    ElMessage.warning('请勾选待审批的预约记录')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确定批量通过选中的 ${pending.length} 条待审批预约吗？`,
      '提示',
      { type: 'warning' }
    )
  } catch (e) {
    return
  }
  uxBatchLoading.value = true
  try {
    // 走批量接口：后端在一个事务内逐条加锁，避免逐条请求出现部分成功
    const res = await batchApproveBooking({
      bookingIds: pending.map((r) => r.id),
      status: 1
    })
    ElMessage.success(res.msg || `批量通过 ${pending.length} 条预约`)
    loadTable()
  } catch (err) {
    // 错误提示由响应拦截器统一处理（整批失败会整体回滚）
  } finally {
    uxBatchLoading.value = false
  }
}

const loadTable = async () => {
  loading.value = true
  try {
    const res = await getAdminBookingPage({ ...query.value })
    tableData.value = res.data.records || []
    total.value = res.data.total || 0
  } finally {
    loading.value = false
  }
}

const loadRooms = async () => {
  const res = await getAvailableRoomList()
  roomOptions.value = res.data || []
}

const handleSearch = () => {
  query.value.pageNum = 1
  loadTable()
}

const handleSizeChange = () => {
  query.value.pageNum = 1
  loadTable()
}

const handlePass = async (row) => {
  try {
    await ElMessageBox.confirm(`确定通过「${row.roomName}」的预约吗？`, '提示', {
      type: 'warning'
    })
  } catch (e) {
    return
  }
  try {
    await approveBooking({ bookingId: row.id, status: 1 })
    ElMessage.success('审批通过')
    loadTable()
  } catch (err) {
    // 错误提示由响应拦截器统一处理
  }
}

const openRejectDialog = (row) => {
  rejectForm.value = { bookingId: row.id, rejectReason: '' }
  rejectDialogVisible.value = true
}

const submitReject = async () => {
  if (!rejectFormRef.value) return
  await rejectFormRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      await approveBooking({
        bookingId: rejectForm.value.bookingId,
        status: 2,
        rejectReason: rejectForm.value.rejectReason
      })
      ElMessage.success('已驳回')
      rejectDialogVisible.value = false
      loadTable()
    } catch (err) {
      // 错误提示由响应拦截器统一处理
    } finally {
      submitting.value = false
    }
  })
}

onMounted(() => {
  loadTable()
  loadRooms()
})
</script>

<style scoped>
.filter-bar {
  display: flex;
  gap: 8px;
}
</style>