import request from '@/utils/request'

// 员工：提交预约 { roomId, startTime, endTime, title, reason }
export function submitBooking(data) {
  return request.post('/booking/submit', data)
}

// 管理员：分页查询全部预约（支持 roomId/resStatus/startTime/endTime 筛选）
export function getAdminBookingPage(params) {
  return request.get('/booking/admin/page', { params })
}

// 员工：分页查询本人预约
export function getUserBookingPage(params) {
  return request.get('/booking/user/page', { params })
}

// 管理员：审批预约 { bookingId, status, rejectReason }，status：1 通过 / 2 驳回
export function approveBooking(data) {
  return request.post('/booking/approve', data)
}

// 管理员：批量审批 { bookingIds, status, rejectReason }，后端一个事务内逐条加锁
export function batchApproveBooking(data) {
  return request.post('/booking/batch-approve', data)
}

// 员工：取消预约（后端支持 待审批0 / 已通过1；会议已开始则不可取消）
export function cancelBooking(id) {
  return request.delete(`/booking/cancel/${id}`)
}