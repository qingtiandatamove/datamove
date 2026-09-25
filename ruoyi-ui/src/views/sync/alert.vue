<template>
  <el-card>
    <div slot="header" class="clearfix">
      <span>告警中心</span>
      <div class="header-actions">
        <el-button v-if="$hasPerm('sync:alert:test')" size="mini" type="primary" icon="el-icon-message"
          @click="onTest">发送测试告警</el-button>
        <el-button v-if="$hasPerm('sync:alert:remove')" size="mini" icon="el-icon-delete"
          @click="onClear">清理</el-button>
      </div>
    </div>

    <!-- 概览: 一眼看出"告警到底发没发出去" -->
    <el-row :gutter="12" class="stat-row">
      <el-col :span="4">
        <div class="stat-card">
          <div class="stat-label">今日告警</div>
          <div class="stat-value">{{ stats.today || 0 }}</div>
        </div>
      </el-col>
      <el-col :span="4">
        <div class="stat-card">
          <div class="stat-label">累计告警</div>
          <div class="stat-value">{{ stats.total || 0 }}</div>
        </div>
      </el-col>
      <el-col :span="4">
        <div class="stat-card stat-ok">
          <div class="stat-label">发送成功</div>
          <div class="stat-value">{{ stats.success || 0 }}</div>
        </div>
      </el-col>
      <el-col :span="4">
        <div class="stat-card stat-fail">
          <div class="stat-label">发送失败</div>
          <div class="stat-value">{{ stats.failed || 0 }}</div>
        </div>
      </el-col>
      <el-col :span="4">
        <div class="stat-card stat-skip">
          <div class="stat-label">未送达</div>
          <div class="stat-value">{{ (stats.pending || 0) + (stats.skipped || 0) }}</div>
          <div class="stat-sub">待发送 {{ stats.pending || 0 }} / 未发送 {{ stats.skipped || 0 }}</div>
        </div>
      </el-col>
      <el-col :span="4">
        <div class="stat-card">
          <div class="stat-label">成功率</div>
          <div class="stat-value">{{ successRate }}</div>
        </div>
      </el-col>
    </el-row>

    <el-form :inline="true" :model="query" class="filter-form">
      <el-form-item>
        <el-select v-model="query.status" clearable placeholder="状态" size="small" style="width:120px">
          <el-option label="待发送" value="0" />
          <el-option label="成功" value="1" />
          <el-option label="失败" value="2" />
          <el-option label="未发送(无通道)" value="3" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-select v-model="query.channel" clearable placeholder="通道" size="small" style="width:110px">
          <el-option label="钉钉" value="DINGTALK" />
          <el-option label="邮件" value="MAIL" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-select v-model="query.alertType" clearable placeholder="场景" size="small" style="width:110px">
          <el-option label="全量同步" value="TASK" />
          <el-option label="表结构" value="DDL" />
          <el-option label="增量同步" value="CANAL" />
          <el-option label="数据校验" value="VERIFY" />
          <el-option label="测试" value="TEST" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-input v-model="query.keyword" placeholder="任务名称" size="small" clearable />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" size="small" icon="el-icon-search" @click="load">查询</el-button>
        <el-button size="small" icon="el-icon-refresh" @click="onReset">重置</el-button>
      </el-form-item>
    </el-form>

    <el-table :data="page.rows" v-loading="loading" border class="alert-table">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="taskName" label="任务" min-width="140" show-overflow-tooltip />
      <el-table-column label="场景" width="100">
        <template slot-scope="s">
          <el-tag size="mini" :type="typeTag(s.row.alertType)">{{ typeLabel(s.row.alertType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="通道" width="80">
        <template slot-scope="s">{{ channelLabel(s.row.channel) }}</template>
      </el-table-column>
      <el-table-column prop="subject" label="标题" min-width="200" show-overflow-tooltip />
      <el-table-column label="状态" width="90">
        <template slot-scope="s">
          <el-tag size="mini" :type="statusTag(s.row.status)">{{ statusLabel(s.row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="重试" width="70" align="center">
        <template slot-scope="s">{{ s.row.retryCount || 0 }}</template>
      </el-table-column>
      <el-table-column prop="createTime" label="创建时间" width="160" />
      <el-table-column prop="sendTime" label="发送时间" width="160" />
      <el-table-column label="操作" min-width="150" fixed="right">
        <template slot-scope="s">
          <el-button size="mini" @click="onDetail(s.row)">详情</el-button>
          <el-button v-if="$hasPerm('sync:alert:retry')" size="mini" type="warning"
            :disabled="s.row.status === '1'" @click="onRetry(s.row)">重试</el-button>
          <el-button v-if="$hasPerm('sync:alert:remove')" size="mini" type="danger"
            @click="onDel(s.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination
      style="margin-top:16px" background layout="prev, pager, next, total"
      :total="page.total" :page-size="query.pageSize" :current-page.sync="query.pageNum" @current-change="load" />

    <!-- 详情: 失败原因这里看, 列表里放不下 -->
    <el-dialog title="告警详情" :visible.sync="detailDialog" width="640px">
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="ID">{{ detail.id }}</el-descriptions-item>
        <el-descriptions-item label="任务">{{ detail.taskName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="场景">{{ typeLabel(detail.alertType) }}</el-descriptions-item>
        <el-descriptions-item label="通道">{{ channelLabel(detail.channel) }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ statusLabel(detail.status) }}</el-descriptions-item>
        <el-descriptions-item label="重试次数">{{ detail.retryCount || 0 }}</el-descriptions-item>
        <el-descriptions-item label="投递目标" :span="2">
          <span class="break-all">{{ detail.target }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="标题" :span="2">{{ detail.subject }}</el-descriptions-item>
        <el-descriptions-item label="正文" :span="2">
          <pre class="content-pre">{{ detail.content }}</pre>
        </el-descriptions-item>
        <el-descriptions-item label="失败原因" :span="2" v-if="detail.errorMsg">
          <span class="err-msg">{{ detail.errorMsg }}</span>
        </el-descriptions-item>
      </el-descriptions>
      <div slot="footer"><el-button type="primary" @click="detailDialog=false">关 闭</el-button></div>
    </el-dialog>

    <!-- 测试告警: 配完通道先验一遍能不能收到 -->
    <el-dialog title="发送测试告警" :visible.sync="testDialog" width="520px">
      <el-form :model="testForm" label-width="90px">
        <el-form-item label="通道">
          <el-radio-group v-model="testForm.channel">
            <el-radio label="DINGTALK">钉钉机器人</el-radio>
            <el-radio label="MAIL">邮件</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="投递目标">
          <el-input v-model="testForm.target"
            :placeholder="testForm.channel === 'MAIL' ? '收件邮箱, 多个用逗号分隔' : '钉钉机器人 Webhook 地址'" />
        </el-form-item>
        <el-form-item label="标题"><el-input v-model="testForm.subject" placeholder="默认: 告警通道测试" /></el-form-item>
        <el-form-item label="正文"><el-input v-model="testForm.content" type="textarea" :rows="3" placeholder="留空则发送默认测试文案" /></el-form-item>
        <div class="test-tip">
          邮件走全局 SMTP 配置(<code>sync.mail.*</code>): 未开启或未配 host 时发送会失败,
          失败记录可以在列表里看到具体原因。
        </div>
      </el-form>
      <div slot="footer">
        <el-button @click="testDialog=false">取消</el-button>
        <el-button type="primary" :loading="testSending" @click="onSendTest">发 送</el-button>
      </div>
    </el-dialog>
  </el-card>
</template>

<script>
import { pageAlert, alertStats, retryAlert, testAlert, deleteAlert, clearAlert } from '@/api/datamove'

export default {
  data () {
    return {
      query: { status: '', channel: '', alertType: '', keyword: '', pageNum: 1, pageSize: 10 },
      page: { rows: [], total: 0 }, loading: false, stats: {},
      detailDialog: false, detail: {},
      testDialog: false, testSending: false,
      testForm: { channel: 'DINGTALK', target: '', subject: '', content: '' }
    }
  },
  computed: {
    successRate () {
      const done = (this.stats.success || 0) + (this.stats.failed || 0)
      if (!done) return '-'
      return Math.round((this.stats.success || 0) * 100 / done) + '%'
    }
  },
  mounted () { this.load(); this.loadStats() },
  methods: {
    load () {
      this.loading = true
      pageAlert(this.query).then(r => { this.page = r.data }).catch(() => {})
        .finally(() => { this.loading = false })
    },
    loadStats () { alertStats().then(r => { this.stats = r.data || {} }).catch(() => {}) },
    onReset () {
      this.query = { status: '', channel: '', alertType: '', keyword: '', pageNum: 1, pageSize: 10 }
      this.load()
    },
    onDetail (row) { this.detail = row; this.detailDialog = true },
    onRetry (row) {
      retryAlert(row.id).then(r => {
        const rec = r.data || {}
        if (rec.status === '1') this.$message.success('重发成功')
        else this.$message.error('重发失败: ' + (rec.errorMsg || '未知原因'))
        this.load(); this.loadStats()
      }).catch(() => {})
    },
    onDel (row) {
      this.$confirm('删除这条告警记录?', '提示', { type: 'warning' })
        .then(() => deleteAlert(row.id)).then(() => { this.$message.success('已删除'); this.load(); this.loadStats() }).catch(() => {})
    },
    onClear () {
      this.$prompt('清理多少天之前的告警记录?', '清理', {
        inputValue: '30', inputPattern: /^[1-9]\d*$/, inputErrorMessage: '请输入正整数'
      }).then(({ value }) => clearAlert(value))
        .then(r => { this.$message.success('已清理 ' + (r.data || 0) + ' 条'); this.load(); this.loadStats() })
        .catch(() => {})
    },
    onTest () { this.testDialog = true },
    onSendTest () {
      if (!this.testForm.target) { this.$message.warning('请填写投递目标'); return }
      this.testSending = true
      testAlert(this.testForm).then(r => {
        const rec = r.data || {}
        if (rec.status === '1') { this.$message.success('发送成功, 请查收'); this.testDialog = false }
        else this.$message.error('发送失败: ' + (rec.errorMsg || '未知原因'))
        this.load(); this.loadStats()
      }).catch(() => {}).finally(() => { this.testSending = false })
    },

    channelLabel (c) { return { DINGTALK: '钉钉', MAIL: '邮件', NONE: '无' }[c] || c },
    statusLabel (s) { return { 0: '待发送', 1: '成功', 2: '失败', 3: '未发送' }[s] || s },
    statusTag (s) { return { 0: 'info', 1: 'success', 2: 'danger', 3: 'warning' }[s] || 'info' },
    typeLabel (t) {
      return { TASK: '全量同步', DDL: '表结构', CANAL: '增量同步', VERIFY: '数据校验', TEST: '测试' }[t] || t || '-'
    },
    typeTag (t) {
      return { TASK: '', DDL: 'warning', CANAL: 'success', VERIFY: 'danger', TEST: 'info' }[t] || 'info'
    }
  }
}
</script>

<style scoped>
.header-actions { float: right }
.stat-row { margin-bottom: 16px }
.stat-card {
  border: 1px solid var(--color-border, #ebeef5); border-radius: 4px; padding: 10px 12px; text-align: center;
}
.stat-label { color: #909399; font-size: 12px }
.stat-value { font-size: 20px; font-weight: 700; margin-top: 4px; color: var(--color-text-primary) }
.stat-ok .stat-value { color: #67C23A }
.stat-fail .stat-value { color: #F56C6C }
.stat-skip .stat-value { color: #E6A23C }
.stat-sub { color: #909399; font-size: 11px; margin-top: 2px }
.filter-form { margin-bottom: 4px }
.break-all { word-break: break-all }
.content-pre {
  white-space: pre-wrap; word-break: break-all; margin: 0; max-height: 220px; overflow: auto;
  font-family: Menlo, Consolas, monospace; font-size: 12px;
}
.err-msg { color: #F56C6C; word-break: break-all }
.test-tip { color: #909399; font-size: 12px; line-height: 18px }

/* 操作列: 与项目内其它列表保持一致的紧凑排布 */
.alert-table >>> td:last-child .cell {
  display: flex; flex-wrap: nowrap; align-items: center; gap: 8px; white-space: nowrap;
}
.alert-table >>> td:last-child .cell .el-button { flex-shrink: 0; padding: 5px 8px; font-size: 12px }
.alert-table >>> td:last-child .cell .el-button + .el-button { margin-left: 0 }
</style>
