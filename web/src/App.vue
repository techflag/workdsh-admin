<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { Message } from '@arco-design/web-vue'
import { api, post, patch, download } from './api'

const me = ref(null)
const page = ref('overview')
const loading = ref(false)
const error = ref('')
const loginForm = reactive({ email: '', password: '' })
const passwordForm = reactive({ currentPassword: '', newPassword: '' })
const members = ref([])
const orders = ref([])
const inbox = ref([])
const handoffInbox = ref([])
const handoffSent = ref([])
const colleagues = ref([])
const handoffForm = reactive({ recipientId: '', summary: '' })
const handoffSending = ref(false)
const handoffRequestKey = ref(crypto.randomUUID())
watch(() => [handoffForm.recipientId, handoffForm.summary], () => { handoffRequestKey.value = crypto.randomUUID() })
const handoffResolution = reactive({})
const audits = ref([])
const order = ref(null)
const reviewEvents = ref([])
const orderSources = ref([])
const sourcePreview = ref(null)
const memberDialog = ref(false)
const orderDialog = ref(false)
const secretDialog = ref(false)
const tempSecret = ref('')
const secretKind = ref('password')
const memberForm = reactive({ email: '', displayName: '', role: 'MEMBER' })
const orderForm = reactive({ customerName: '', sourceType: 'EXCEL', sourceName: '', lines: [] })
const reviewerId = ref('')
const returnReason = ref('')
let inboxTimer = null
let inboxPolling = false
let knownInboxIds = new Set()
let knownHandoffIds = new Set()
let knownOrderStatuses = new Map()
let authEpoch = 0
sessionStorage.removeItem('workdsh-admin-token')

const isAdmin = computed(() => me.value?.role === 'OWNER' || me.value?.role === 'ADMIN')
const returnedCount = computed(() => orders.value.filter(item => item.creatorId === me.value?.id && item.status === 'CHANGES_REQUESTED').length)
const nav = computed(() => [
  { id: 'overview', label: '组织概览' },
  ...(isAdmin.value ? [{ id: 'members', label: '成员与角色' }, { id: 'admin-orders', label: '订单授权' }] : []),
  { id: 'orders', label: '我的订单' },
  { id: 'handoffs', label: '协作交接' },
  { id: 'inbox', label: '复核待办' },
  ...(isAdmin.value ? [{ id: 'audit', label: '审计记录' }] : []),
])
const currentTitle = computed(() => order.value ? `订单 · ${order.value.customerName}` : nav.value.find(x => x.id === page.value)?.label || '工作台')

function tell(e) { error.value = e?.message || '操作失败'; Message.error(error.value) }
async function login() {
  error.value = ''; loading.value = true
  try {
    const result = await post('/auth/browser-login', loginForm)
    authEpoch++
    me.value = result
    loginForm.password = ''
    await load()
    startInboxPolling()
  } catch(e) { tell(e) } finally { loading.value = false }
}
async function logout() {
  try {
    await post('/auth/logout', {})
    stopInboxPolling()
    authEpoch++; me.value = null; order.value = null
  } catch (e) { tell(e) }
}
async function openDsh() {
  const tab = window.open('about:blank', '_blank')
  if (!tab) return tell(new Error('请允许打开新标签页'))
  try {
    const launch = await post('/dsh/launch', {})
    const form = document.createElement('form')
    form.method = 'POST'
    form.action = `${launch.gatewayOrigin}/launch`
    tab.name = `workdsh-${Date.now()}`
    form.target = tab.name
    const ticket = document.createElement('input')
    ticket.type = 'hidden'
    ticket.name = 'ticket'
    ticket.value = launch.ticket
    form.append(ticket)
    document.body.append(form)
    form.submit()
    form.remove()
  } catch (e) { tab.close(); tell(e) }
}
async function changePassword() {
  try {
    await post('/auth/change-password', passwordForm)
    stopInboxPolling()
    authEpoch++; me.value = null
    passwordForm.currentPassword = ''; passwordForm.newPassword = ''
    Message.success('密码已设置，请重新登录')
  } catch(e) { tell(e) }
}
async function load() {
  if (!me.value || me.value.mustChangePassword) return
  try {
    const calls = [api('/members'), api('/orders'), api('/reviews/inbox'),
      api('/collaboration/colleagues'), api('/collaboration/inbox'), api('/collaboration/sent')]
    if (isAdmin.value) calls.push(api('/admin/members'), api('/admin/orders'), api('/admin/audit'))
    const results = await Promise.all(calls)
    members.value = isAdmin.value ? results[6] : results[0]
    orders.value = results[1]
    knownOrderStatuses = new Map(orders.value.map(item => [item.id, item.status]))
    inbox.value = results[2]
    colleagues.value = results[3]
    handoffInbox.value = results[4]
    handoffSent.value = results[5]
    knownHandoffIds = new Set(handoffInbox.value.map(item => item.id))
    knownInboxIds = new Set(inbox.value.map(item => item.id))
    audits.value = isAdmin.value ? results[8] : []
    if (isAdmin.value) adminOrders.value = results[7]
  } catch(e) { if (e.status === 401) { authEpoch++; me.value = null } else tell(e) }
}
async function pollInbox() {
  if (!me.value || me.value.mustChangePassword || inboxPolling) return
  inboxPolling = true
  const epoch = authEpoch
  try {
    const [latest, latestOrders, latestHandoffs, latestSent] = await Promise.all([
      api('/reviews/inbox'), api('/orders'), api('/collaboration/inbox'), api('/collaboration/sent')])
    if (authEpoch !== epoch || !me.value) return
    const newlyAssigned = latest.filter(item => !knownInboxIds.has(item.id))
    const newlyReturned = latestOrders.filter(item => item.creatorId === me.value.id
      && item.status === 'CHANGES_REQUESTED' && knownOrderStatuses.get(item.id) !== 'CHANGES_REQUESTED')
    inbox.value = latest
    const newHandoffs = latestHandoffs.filter(item => !knownHandoffIds.has(item.id))
    handoffInbox.value = latestHandoffs
    handoffSent.value = latestSent
    knownHandoffIds = new Set(latestHandoffs.map(item => item.id))
    orders.value = latestOrders
    knownInboxIds = new Set(latest.map(item => item.id))
    knownOrderStatuses = new Map(latestOrders.map(item => [item.id, item.status]))
    if (newlyAssigned.length) Message.info(`收到 ${newlyAssigned.length} 项新复核待办`)
    if (newHandoffs.length) Message.info(`收到 ${newHandoffs.length} 条同事交接`)
    if (newlyReturned.length) Message.warning(`有 ${newlyReturned.length} 项订单被退回，请刷新详情`)
  } catch (e) {
    if (e.status === 401) {
      stopInboxPolling()
      authEpoch++
      me.value = null
      order.value = null
    }
  } finally { inboxPolling = false }
}
function startInboxPolling() {
  if (!inboxTimer && me.value && !me.value.mustChangePassword) inboxTimer = window.setInterval(pollInbox, 10_000)
}
function stopInboxPolling() {
  if (inboxTimer) window.clearInterval(inboxTimer)
  inboxTimer = null
  knownInboxIds = new Set()
  knownHandoffIds = new Set()
  knownOrderStatuses = new Map()
}
async function refresh() { await load(); if (order.value) await openOrder(order.value.id) }
async function sendHandoff() {
  if (handoffSending.value) return
  if (!handoffForm.recipientId || !handoffForm.summary.trim()) return Message.warning('请选择同事并填写交接内容')
  handoffSending.value = true
  try {
    await post('/collaboration/handoffs', { recipientId: handoffForm.recipientId,
      summary: handoffForm.summary.trim(), requestKey: handoffRequestKey.value })
    handoffForm.recipientId = ''; handoffForm.summary = ''
    await load(); Message.success('已交接给同事')
  } catch (e) { tell(e) } finally { handoffSending.value = false }
}
async function completeHandoff(item) {
  const resolution = handoffResolution[item.id]?.trim()
  if (!resolution) return Message.warning('请填写处理结果')
  try {
    await post(`/collaboration/handoffs/${item.id}/complete`, { resolution })
    delete handoffResolution[item.id]
    await load(); Message.success('已回执，发送人可以查看结果')
  } catch (e) { tell(e) }
}
const openHandoffCount = computed(() => handoffInbox.value.filter(item => item.status === 'OPEN').length)
const adminOrders = ref([])
async function navigate(id) { page.value = id; order.value = null; reviewEvents.value = []; orderSources.value = []; sourcePreview.value = null; await load() }
async function openOrder(id) {
  try {
    if (order.value?.id !== id) sourcePreview.value = null
    const [detail, events, sources] = await Promise.all([api(`/orders/${id}`), api(`/orders/${id}/events`), api(`/orders/${id}/sources`)])
    order.value = detail; reviewEvents.value = events; orderSources.value = sources
  } catch(e) { tell(e) }
}
async function uploadSource(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file || !order.value) return
  try {
    const form = new FormData()
    form.set('file', file)
    form.set('expectedRevision', String(order.value.revision))
    await api(`/orders/${order.value.id}/sources`, { method: 'POST', body: form })
    sourcePreview.value = null
    await openOrder(order.value.id)
    Message.success('原始文件已保存，复核员可以下载核对')
  } catch(e) { tell(e) }
}
async function previewSource(source) {
  try { sourcePreview.value = await api(`/orders/${order.value.id}/sources/${source.id}/preview`) }
  catch(e) { tell(e) }
}
async function importLine(row) {
  try {
    order.value = await post(`/orders/${order.value.id}/lines/import`, {
      sourceId: sourcePreview.value.sourceId, locator: row.locator, expectedRevision: order.value.revision,
    })
    Message.success(`已加入第 ${row.rowNumber} 行，请核对库存 SKU`)
    await load()
  } catch(e) { tell(e) }
}
async function downloadSource(source) {
  try { await download(`/orders/${order.value.id}/sources/${source.id}/download`, source.fileName) }
  catch(e) { tell(e) }
}
async function addMember() {
  try {
    const created = await post('/admin/members', memberForm)
    tempSecret.value = created.temporaryPassword
    secretKind.value = 'password'
    memberDialog.value = false; secretDialog.value = true
    memberForm.email = ''; memberForm.displayName = ''; memberForm.role = 'MEMBER'
    await load()
  } catch(e) { tell(e) }
}
async function issueRuntime(member) {
  try {
    const issued = await post(`/admin/members/${member.id}/runtime-credential`, {})
    tempSecret.value = issued.runtimeToken
    secretKind.value = 'runtime'
    secretDialog.value = true
    Message.success(`已为 ${member.displayName} 创建 DSH 实例凭据`)
  } catch(e) { tell(e) }
}
async function toggleMember(member) {
  try {
    await patch(`/admin/members/${member.id}`, { active: !member.active, expectedRevision: member.revision })
    await load()
  } catch(e) { tell(e) }
}
async function createOrder() {
  try {
    const created = await post('/orders', orderForm)
    orderDialog.value = false
    Object.assign(orderForm, { customerName: '', sourceType: 'EXCEL', sourceName: '', lines: [] })
    await load(); await openOrder(created.id)
    Message.success('订单已保存')
  } catch(e) { tell(e) }
}
async function submitReview() {
  if (!reviewerId.value) return Message.warning('请选择复核员')
  if (!orderSources.value.length) return Message.warning('请先上传原始订单文件')
  if (!order.value.lines.length) return Message.warning('请先确认至少一条订单行')
  try {
    order.value = await post(`/orders/${order.value.id}/submit-review`, { reviewerId: reviewerId.value, expectedRevision: order.value.revision })
    reviewEvents.value = await api(`/orders/${order.value.id}/events`)
    await load(); Message.success('已交给复核员')
  } catch(e) { tell(e) }
}
async function decide(decision) {
  try {
    order.value = await post(`/orders/${order.value.id}/review`, { decision, comment: returnReason.value, expectedRevision: order.value.revision })
    reviewEvents.value = await api(`/orders/${order.value.id}/events`)
    returnReason.value = ''; await load()
    Message.success(decision === 'APPROVED' ? '复核已通过' : '已退回销售修改')
  } catch(e) { tell(e) }
}
async function updateMatch(line) {
  try {
    order.value = await patch(`/orders/${order.value.id}/lines/${line.id}/match`, { internalSku: line.internalSku, expectedRevision: order.value.revision })
    await load(); Message.success('SKU 匹配已保存')
  } catch(e) { tell(e) }
}
function person(id) { return members.value.find(m => m.id === id)?.displayName || id?.slice(0, 8) || '—' }
function status(value) { return ({ DRAFT: '待整理', IN_REVIEW: '待复核', CHANGES_REQUESTED: '已退回', APPROVED: '已通过' })[value] || value }
function roleLabel(value) { return ({ OWNER: '所有者', ADMIN: '管理员', MEMBER: '成员' })[value] || value }
onMounted(async () => {
  try { me.value = await api('/auth/me'); await load(); startInboxPolling() }
  catch { me.value = null }
})
onUnmounted(stopInboxPolling)
</script>

<template>
  <div v-if="!me" class="login-wrap">
    <div class="login-card">
      <div class="brand" style="color:#202633;padding:0 0 28px"><b>W</b> WorkDSH <small>ENTERPRISE CONSOLE</small></div>
      <h1>登录企业空间</h1><p class="subtitle">管理成员与订单，让协作有明确的归属。</p>
      <form class="form-stack" @submit.prevent="login">
        <label>邮箱<a-input v-model="loginForm.email" type="email" autocomplete="username" placeholder="name@company.com" /></label>
        <label>密码<a-input-password v-model="loginForm.password" autocomplete="current-password" placeholder="输入密码" /></label>
        <a-alert v-if="error" type="error">{{ error }}</a-alert>
        <a-button type="primary" html-type="submit" long :loading="loading">登录</a-button>
      </form>
    </div>
  </div>

  <div v-else-if="me.mustChangePassword" class="login-wrap">
    <div class="login-card"><h1>首次登录</h1><p class="subtitle">请把管理员提供的临时密码改为自己的密码。</p>
      <form class="form-stack" @submit.prevent="changePassword">
        <label>临时密码<a-input-password v-model="passwordForm.currentPassword" /></label>
        <label>新密码（至少 12 位）<a-input-password v-model="passwordForm.newPassword" /></label>
        <a-button type="primary" html-type="submit" long>设置密码</a-button>
        <a-button long @click="logout">退出</a-button>
      </form>
    </div>
  </div>

  <div v-else class="shell">
    <aside class="rail">
      <div class="brand"><b>W</b> WorkDSH <small>ENTERPRISE CONSOLE</small></div>
      <button v-for="item in nav" :key="item.id" class="nav" :class="{active:page===item.id&&!order}" @click="navigate(item.id)">{{ item.label }}<span v-if="item.id==='inbox' && inbox.length" class="inbox-count">{{ inbox.length }}</span><span v-if="item.id==='handoffs' && openHandoffCount" class="inbox-count">{{ openHandoffCount }}</span><span v-if="item.id==='orders' && returnedCount" class="inbox-count">{{ returnedCount }}</span></button>
      <div class="rail-footer">{{ me.displayName }} · {{ me.role === 'OWNER' ? '所有者' : me.role === 'ADMIN' ? '管理员' : '成员' }}<br>{{ me.email }}<br><a-button size="mini" type="text" style="margin-top:8px;color:#aab6ca" @click="logout">退出登录</a-button></div>
    </aside>
    <main class="main">
      <div class="topline"><div><h1>{{ currentTitle }}</h1><p class="subtitle">{{ order ? `订单号 ${order.id}` : '同一套企业身份，连接管理后台与业务协作。' }}</p></div><a-button @click="refresh">刷新</a-button></div>
      <nav class="mobile-nav" aria-label="工作台导航"><button v-for="item in nav" :key="item.id" class="mobile-nav-item" :class="{active:page===item.id&&!order}" @click="navigate(item.id)">{{ item.label }}<span v-if="item.id==='inbox' && inbox.length" class="inbox-count">{{ inbox.length }}</span><span v-if="item.id==='handoffs' && openHandoffCount" class="inbox-count">{{ openHandoffCount }}</span><span v-if="item.id==='orders' && returnedCount" class="inbox-count">{{ returnedCount }}</span></button><button class="mobile-nav-item" @click="logout">退出</button></nav>

      <template v-if="order">
        <div class="section-head"><a-button type="text" @click="order=null">← 返回列表</a-button><a-tag color="blue">{{ status(order.status) }}</a-tag></div>
        <div class="order-layout">
          <div class="panel"><h2>客户订单明细</h2><p class="muted">来源声明：{{ order.sourceType }} · {{ order.sourceName }}。Excel 可预览并逐行确认；PDF 和截图仍需人工录入。</p>
            <div class="section-head" style="margin-top:22px"><h2>原始订单文件</h2><label v-if="order.creatorId===me.id && ['DRAFT','CHANGES_REQUESTED'].includes(order.status)" class="source-upload">＋ 上传原件<input type="file" accept=".png,.jpg,.jpeg,.pdf,.xls,.xlsx" @change="uploadSource"/></label></div>
            <p v-if="!orderSources.length" class="muted">尚未上传原件；提交复核前必须提供截图、PDF 或 Excel 文件。</p>
            <div v-for="source in orderSources" :key="source.id" class="source-row"><div><b>{{ source.fileName }}</b><p class="muted">{{ source.sourceType }} · {{ (source.sizeBytes / 1024).toFixed(1) }} KB · SHA-256 {{ source.sha256.slice(0, 12) }}…</p></div><div class="actions"><a-button v-if="source.sourceType==='EXCEL'" size="small" @click="previewSource(source)">预览表格行</a-button><a-button size="small" @click="downloadSource(source)">下载核对</a-button></div></div>
            <div v-if="sourcePreview" class="extract-preview"><h3>表格行建议 · {{ orderSources.find(s=>s.id===sourcePreview.sourceId)?.fileName }}</h3><p class="muted">{{ sourcePreview.message }}。客户参考编号不是客户 SKU；价格仅供核对，尚未进入订单。</p><a-table :data="sourcePreview.candidates" :pagination="{pageSize:10}" row-key="locator"><template #columns><a-table-column title="位置"><template #cell="{record}">{{ record.sheetName }} · 第 {{ record.rowNumber }} 行</template></a-table-column><a-table-column title="客户参考编号" data-index="customerReference"/><a-table-column title="客户 SKU"><template #cell="{record}">{{ record.customerSku || '未提供' }}</template></a-table-column><a-table-column title="规格/名称" data-index="customerName"/><a-table-column title="数量" data-index="quantity"/><a-table-column title="原件单价" data-index="unitPriceText"/><a-table-column title="操作"><template #cell="{record}"><a-button size="small" :disabled="order.lines.some(line=>line.sourceId===sourcePreview.sourceId&&line.sourceLocator===record.locator) || order.creatorId!==me.id || !['DRAFT','CHANGES_REQUESTED'].includes(order.status)" @click="importLine(record)">{{ order.lines.some(line=>line.sourceId===sourcePreview.sourceId&&line.sourceLocator===record.locator) ? '已加入' : '加入订单' }}</a-button></template></a-table-column></template></a-table></div>
            <a-table :data="order.lines" :pagination="false" row-key="id" style="margin-top:18px">
              <template #columns>
                <a-table-column title="客户 SKU"><template #cell="{record}">{{ record.customerSku || '未提供' }}</template></a-table-column>
                <a-table-column title="客户参考编号" data-index="customerReference" />
                <a-table-column title="客户名称" data-index="customerName" />
                <a-table-column title="来源位置"><template #cell="{record}">{{ record.sourceLocator || '人工录入' }}</template></a-table-column>
                <a-table-column title="数量" data-index="quantity" />
                <a-table-column title="库存 SKU"><template #cell="{record}"><a-input v-if="order.creatorId===me.id && ['DRAFT','CHANGES_REQUESTED'].includes(order.status)" v-model="record.internalSku" placeholder="待确认" size="small"/><span v-else>{{ record.internalSku || '未匹配' }}</span></template></a-table-column>
                <a-table-column title="操作" :width="85"><template #cell="{record}"><a-button v-if="order.creatorId===me.id && ['DRAFT','CHANGES_REQUESTED'].includes(order.status)" type="text" size="small" @click="updateMatch(record)">保存</a-button></template></a-table-column>
              </template>
            </a-table>
            <div v-if="order.reviewerId===me.id && order.status==='IN_REVIEW'" style="margin-top:28px"><h2>我的复核结论</h2><p v-if="order.lines.some(line=>!line.internalSku)" class="muted">仍有订单行未确认库存 SKU，请退回销售核对。</p><a-textarea v-model="returnReason" placeholder="退回时请说明需要核对的内容" :max-length="1000" style="margin:14px 0"/><div class="actions"><a-button type="primary" :disabled="order.lines.some(line=>!line.internalSku)" @click="decide('APPROVED')">通过</a-button><a-button @click="decide('CHANGES_REQUESTED')">退回修改</a-button></div></div>
          </div>
          <div class="order-aside"><h3>流转状态</h3><p>创建人：{{ person(order.creatorId) }}</p><p>复核人：{{ person(order.reviewerId) }}</p><p>当前版本：V{{ order.revision }}</p>
            <h3 style="margin-top:24px">复核记录</h3><p v-if="!reviewEvents.length" class="muted">尚无复核记录</p><div v-for="event in reviewEvents" :key="event.id" class="review-event"><b>{{ event.action==='SUBMITTED' ? '提交复核' : event.action==='CHANGES_REQUESTED' ? '退回修改' : '复核通过' }}</b><span class="muted"> · {{ person(event.actorId) }}</span><p v-if="event.comment">{{ event.comment }}</p></div>
            <template v-if="order.creatorId===me.id && ['DRAFT','CHANGES_REQUESTED'].includes(order.status)"><hr style="border:0;border-top:1px solid #dce2ec;margin:20px 0"/><h3>@ 同事复核</h3><p class="muted">确认至少一条订单行并上传原件后，复核员将收到待办，可下载原件核对。</p><a-select v-model="reviewerId" placeholder="选择复核员" style="width:100%"><a-option v-for="m in members.filter(m=>m.id!==me.id&&m.active&&!m.mustChangePassword)" :key="m.id" :value="m.id">{{ m.displayName }}</a-option></a-select><a-button type="primary" long style="margin-top:12px" :disabled="!orderSources.length || !order.lines.length" @click="submitReview">提交复核</a-button></template>
          </div>
        </div>
      </template>

      <template v-else-if="page==='overview'">
        <div class="stats"><div class="panel"><div class="muted">有效成员</div><div class="stat-value">{{ members.filter(x=>x.active).length }}</div></div><div class="panel"><div class="muted">待我处理的交接</div><div class="stat-value">{{ openHandoffCount }}</div></div><div class="panel"><div class="muted">我发出的交接</div><div class="stat-value">{{ handoffSent.length }}</div></div></div>
        <div class="section-head"><h2>协作入口</h2></div><div class="panel"><p>在自己的 DSH 实例里分析问题并 @ 同事；对方在自己的实例或这里接收待办、回执结果。订单只是可选示例，不是协作的前提。</p><div class="actions"><a-button type="primary" @click="openDsh">打开我的 DSH</a-button><a-button @click="navigate('handoffs')">查看协作交接</a-button></div></div>
      </template>

      <template v-else-if="page==='handoffs'">
        <div class="section-head"><h2>给同事交接</h2></div>
        <div class="panel form-stack" style="max-width:760px">
          <p class="muted">也可以在自己的 DSH 会话里直接提出“@ 某位同事处理这件事”。交接记录归成员所有，不共享整段会话。</p>
          <label>接收人<a-select v-model="handoffForm.recipientId" placeholder="选择同组织成员"><a-option v-for="item in colleagues" :key="item.id" :value="item.id">{{ item.displayName }} · {{ item.email }}</a-option></a-select></label>
          <label>需要处理的事<a-textarea v-model="handoffForm.summary" :max-length="2000" :auto-size="{minRows:3,maxRows:7}" placeholder="写清要对方处理什么、期望怎样回执"/></label>
          <div><a-button type="primary" :loading="handoffSending" @click="sendHandoff">@ 同事并交接</a-button></div>
        </div>
        <div class="section-head" style="margin-top:28px"><h2>待我处理</h2></div>
        <div class="panel"><p v-if="!handoffInbox.length" class="muted">暂无同事交接。</p>
          <div v-for="item in handoffInbox" :key="item.id" class="review-event"><b>{{ item.senderName }} → 我</b> <a-tag :color="item.status==='OPEN'?'orange':'green'">{{ item.status==='OPEN'?'待处理':'已完成' }}</a-tag>
            <p>{{ item.summary }}</p><p class="muted">{{ item.createdAt }}</p>
            <p v-if="item.status==='DONE'">处理结果：{{ item.resolution }}</p>
            <div v-else class="actions" style="margin-top:10px"><a-input v-model="handoffResolution[item.id]" placeholder="完成后填写结果" style="max-width:520px"/><a-button type="primary" @click="completeHandoff(item)">完成并回执</a-button></div>
          </div>
        </div>
        <div class="section-head" style="margin-top:28px"><h2>我发出的交接</h2></div>
        <div class="panel"><p v-if="!handoffSent.length" class="muted">尚未发出交接。</p>
          <div v-for="item in handoffSent" :key="item.id" class="review-event"><b>我 → {{ item.recipientName }}</b> <a-tag :color="item.status==='OPEN'?'orange':'green'">{{ item.status==='OPEN'?'等待同事':'已回执' }}</a-tag><p>{{ item.summary }}</p><p v-if="item.resolution">结果：{{ item.resolution }}</p></div>
        </div>
      </template>

      <template v-else-if="page==='members'">
        <div class="section-head"><h2>成员列表</h2><a-button type="primary" @click="memberDialog=true">＋ 直接添加成员</a-button></div>
        <div class="panel"><a-table :data="members" :pagination="false" row-key="id"><template #columns><a-table-column title="成员"><template #cell="{record}"><div class="row-title">{{record.displayName}}</div><div class="muted">{{record.email}}</div></template></a-table-column><a-table-column title="角色"><template #cell="{record}">{{roleLabel(record.role)}}</template></a-table-column><a-table-column title="状态"><template #cell="{record}"><a-tag :color="record.active?'green':'gray'">{{record.active?'启用':'停用'}}</a-tag><a-tag v-if="record.mustChangePassword" color="orange">首次登录待改密</a-tag></template></a-table-column><a-table-column title="操作" :width="200"><template #cell="{record}"><div class="actions"><a-button v-if="record.active&&!record.mustChangePassword" size="small" @click="issueRuntime(record)">DSH 凭据</a-button><a-button v-if="record.role!=='OWNER'" size="small" @click="toggleMember(record)">{{record.active?'停用':'启用'}}</a-button></div></template></a-table-column></template></a-table></div>
      </template>

      <template v-else-if="page==='orders'">
        <div class="section-head"><h2>我的订单</h2><a-button type="primary" @click="orderDialog=true">＋ 新建订单</a-button></div>
        <div class="panel"><a-alert type="info" style="margin-bottom:16px">先建订单，再上传截图、PDF 或 Excel。Excel 可预览带来源位置的表格行并逐条加入；截图/PDF 的结构化提取及库存 SKU 建议仍待接入。</a-alert><a-table :data="orders" row-key="id"><template #columns><a-table-column title="客户" data-index="customerName"/><a-table-column title="来源" data-index="sourceType"/><a-table-column title="状态"><template #cell="{record}"><a-tag>{{status(record.status)}}</a-tag></template></a-table-column><a-table-column title="创建人"><template #cell="{record}">{{person(record.creatorId)}}</template></a-table-column><a-table-column title="操作" :width="100"><template #cell="{record}"><a-button type="text" @click="openOrder(record.id)">打开</a-button></template></a-table-column></template></a-table></div>
      </template>

      <template v-else-if="page==='inbox'">
        <div class="section-head"><h2>待我复核</h2></div><div class="panel"><a-table :data="inbox" row-key="id"><template #columns><a-table-column title="客户" data-index="customerName"/><a-table-column title="提交人"><template #cell="{record}">{{person(record.creatorId)}}</template></a-table-column><a-table-column title="状态"><template #cell="{record}">{{status(record.status)}}</template></a-table-column><a-table-column title="操作"><template #cell="{record}"><a-button type="primary" size="small" @click="openOrder(record.id)">复核</a-button></template></a-table-column></template></a-table></div>
      </template>

      <template v-else-if="page==='admin-orders'">
        <div class="section-head"><h2>组织订单索引</h2></div><div class="panel"><p class="muted">管理员只能看到订单元数据；订单正文须获得明确授权。</p><a-table :data="adminOrders" row-key="id"><template #columns><a-table-column title="客户" data-index="customerName"/><a-table-column title="创建人"><template #cell="{record}">{{person(record.creatorId)}}</template></a-table-column><a-table-column title="状态"><template #cell="{record}">{{status(record.status)}}</template></a-table-column><a-table-column title="订单 ID" data-index="id"/></template></a-table></div>
      </template>

      <template v-else-if="page==='audit'">
        <div class="section-head"><h2>最近操作</h2></div><div class="panel"><a-table :data="audits" row-key="id"><template #columns><a-table-column title="时间" data-index="occurredAt"/><a-table-column title="成员"><template #cell="{record}">{{person(record.actorId)}}</template></a-table-column><a-table-column title="动作" data-index="action"/><a-table-column title="对象" data-index="targetId"/></template></a-table></div>
      </template>
    </main>
  </div>

  <a-modal v-model:visible="memberDialog" title="直接添加成员" @ok="addMember"><div class="form-stack"><label>邮箱<a-input v-model="memberForm.email" type="email"/></label><label>姓名<a-input v-model="memberForm.displayName"/></label><label>组织角色<a-select v-model="memberForm.role"><a-option value="MEMBER">成员</a-option><a-option value="ADMIN">管理员</a-option></a-select></label><p class="muted">添加后生成一次性显示的临时密码，成员首次登录时自行修改。</p></div></a-modal>
  <a-modal v-model:visible="secretDialog" :title="secretKind==='password'?'成员已添加':'DSH 实例凭据已创建'" :footer="false" @cancel="tempSecret=''">
    <p v-if="secretKind==='password'">请通过安全渠道把临时密码交给成员。关闭后不会再次显示。</p>
    <p v-else>仅供该成员的一个 DSH 实例使用。通过服务端配置注入，勿放入浏览器或聊天记录；关闭后不会再次显示。</p>
    <code class="credential">{{tempSecret}}</code><a-button type="primary" style="margin-top:15px" @click="secretDialog=false;tempSecret=''">我已记录</a-button>
  </a-modal>
  <a-modal v-model:visible="orderDialog" title="新建订单" width="920px" @ok="createOrder"><div class="form-stack"><label>客户名称<a-input v-model="orderForm.customerName"/></label><div class="actions"><label style="flex:1">主要来源类型<a-select v-model="orderForm.sourceType"><a-option value="SCREENSHOT">截图</a-option><a-option value="PDF">PDF</a-option><a-option value="EXCEL">Excel</a-option></a-select></label><label style="flex:2">来源备注（人工填写）<a-input v-model="orderForm.sourceName" placeholder="例如邮件主题或文件名"/></label></div><p class="muted">可先创建空订单，再上传原件。Excel 表格行需逐条确认后加入；客户未提供 SKU 时保持空白，设备编号填在“客户参考编号”。</p><div><b>人工补录订单行（可选）</b><div v-for="(line,index) in orderForm.lines" :key="index" class="line" style="margin-top:10px"><a-input v-model="line.customerSku" placeholder="客户 SKU（可空）"/><a-input v-model="line.customerReference" placeholder="客户参考编号"/><a-input v-model="line.customerName" placeholder="规格/名称"/><a-input-number v-model="line.quantity" :min="1"/><a-input v-model="line.internalSku" placeholder="库存 SKU（可空）"/></div><a-button type="text" style="margin-top:8px" @click="orderForm.lines.push({customerSku:'',customerReference:'',customerName:'',quantity:1,internalSku:''})">＋ 添加人工订单行</a-button></div></div></a-modal>
</template>
