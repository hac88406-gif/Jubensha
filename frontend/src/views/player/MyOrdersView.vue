<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fetchMyOrders, cancelOrder, prepay, payNotify } from '../../api/order'

/**
 * 我的订单 —— 玩家端
 * 订单状态（后端 OrderInfo.status）：0-待支付 1-已支付 2-已取消 3-已完成
 * 操作：
 *   - 待支付 → 去支付（prepay → notify 模拟回调）
 *   - 待支付 → 取消订单
 * 全部走真实后端接口。
 */
const loading = ref(false)
const orders = ref([])
const paying = ref(false)

const statusMap = {
  0: { text: '待支付', type: 'warning' },
  1: { text: '已支付', type: 'success' },
  2: { text: '已取消', type: 'info' },
  3: { text: '已完成', type: 'primary' },
}
const payMethodMap = { 0: '未指定', 1: '微信', 2: '支付宝', 3: '线下' }

async function load() {
  loading.value = true
  try {
    const data = await fetchMyOrders()
    orders.value = Array.isArray(data) ? data : []
  } catch (e) {
    orders.value = []
  } finally {
    loading.value = false
  }
}

function fmtPrice(v) {
  if (v === null || v === undefined) return '-'
  return `¥${v}`
}

async function pay(order) {
  paying.value = true
  try {
    const pay = await prepay({ orderNo: order.orderNo, payMethod: 1 })
    await payNotify({
      paymentNo: pay.paymentNo,
      orderNo: pay.orderNo,
      amount: String(pay.amount),
      channel: pay.channel,
      sign: pay.sign,
    })
    ElMessage.success('支付成功！')
    load()
  } catch (e) {
    ElMessage.error(e.message || '支付失败')
  } finally {
    paying.value = false
  }
}

async function cancel(order) {
  try {
    await ElMessageBox.confirm('确定取消该订单吗？', '取消订单', { type: 'warning' })
  } catch {
    return
  }
  try {
    await cancelOrder(order.id)
    ElMessage.success('订单已取消')
    load()
  } catch (e) {
    ElMessage.error(e.message || '取消失败')
  }
}

onMounted(load)
</script>

<template>
  <div class="orders-page">
    <h3 class="page-title">我的订单</h3>
    <div v-loading="loading" class="order-list">
      <el-empty v-if="!loading && orders.length === 0" description="还没有订单，去大厅挑个剧本吧" />
      <el-card v-for="o in orders" :key="o.id" shadow="never" class="order-card">
        <div class="o-row">
          <div class="o-main">
            <div class="o-name">{{ o.scriptName || `剧本 #${o.scriptId}` }}</div>
            <div class="o-meta">
              {{ o.sessionDate }} {{ o.startTime }}-{{ o.endTime }}
              <template v-if="o.shopName"> · {{ o.shopName }}</template>
            </div>
          </div>
          <el-tag :type="statusMap[o.status]?.type || 'info'" size="small">
            {{ statusMap[o.status]?.text || '未知' }}
          </el-tag>
        </div>
        <div class="o-foot">
          <span class="o-detail">人数 {{ o.playerCnt }} · {{ payMethodMap[o.payMethod] }} · {{ o.orderNo }}</span>
          <span class="o-price">{{ fmtPrice(o.amount) }}</span>
          <div class="o-actions">
            <template v-if="o.status === 0">
              <el-button type="primary" size="small" :loading="paying" @click="pay(o)">去支付</el-button>
              <el-button size="small" @click="cancel(o)">取消订单</el-button>
            </template>
            <el-button v-else disabled size="small">已 {{ statusMap[o.status]?.text }}</el-button>
          </div>
        </div>
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.page-title {
  margin: 0 0 18px;
  font-size: 18px;
  color: var(--color-text-main);
}
.order-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-height: 120px;
}
.order-card {
  border-radius: var(--radius);
}
.o-row {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
}
.o-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--color-text-main);
}
.o-meta {
  font-size: 13px;
  color: var(--color-text-sub);
  margin-top: 4px;
}
.o-foot {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed var(--color-border);
}
.o-detail {
  font-size: 12px;
  color: var(--color-text-sub);
  flex: 1;
}
.o-price {
  font-size: 15px;
  font-weight: 700;
  color: var(--color-warning);
}
.o-actions {
  display: flex;
  gap: 8px;
}
</style>