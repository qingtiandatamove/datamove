<template>
  <el-card>
    <div slot="header" class="head">
      <span>登录日志</span>
      <span class="head-tip">谁在什么时候、从哪个 IP 与地点登录, 成功还是失败 (失败记录是撞密码的线索)</span>
    </div>

    <!-- 查询条件 -->
    <el-form :inline="true" :model="query" size="small">
      <el-form-item>
        <el-select v-model="query.status" clearable placeholder="登录状态" style="width:120px">
          <el-option label="成功" value="0" />
          <el-option label="失败" value="1" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-select v-model="query.loginType" clearable placeholder="登录方式" style="width:130px">
          <el-option label="账号密码" value="PASSWORD" />
          <el-option label="短信验证码" value="SMS" />
          <el-option label="邮箱验证码" value="EMAIL" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-input v-model="query.keyword" placeholder="账号 / 昵称 / IP / 地点 / 提示" clearable
          style="width:260px" @keyup.enter.native="onSearch" />
      </el-form-item>
      <el-form-item>
        <el-date-picker v-model="timeRange" type="datetimerange" range-separator="~"
          start-placeholder="开始时间" end-placeholder="结束时间" value-format="yyyy-MM-dd HH:mm:ss"
          style="width:340px" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" @click="onSearch">查询</el-button>
        <el-button icon="el-icon-refresh" @click="onReset">重置</el-button>
      </el-form-item>
      <el-form-item v-if="$hasPerm('sync:loginlog:remove')" style="float:right">
        <el-button type="danger" plain icon="el-icon-delete" @click="onClear">清空日志</el-button>
      </el-form-item>
    </el-form>

    <!-- 列表 -->
    <el-table :data="page.rows" v-loading="loading" border size="small"
      :row-class-name="rowClass" @row-click="onRowClick" style="cursor:pointer">
      <el-table-column prop="loginTime" label="登录时间" width="170" />
      <el-table-column label="登录账号" width="150" show-overflow-tooltip>
        <template slot-scope="s">
          <div>{{ s.row.userName || '-' }}</div>
          <div class="cell-sub">{{ s.row.nickName || '' }}</div>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template slot-scope="s">
          <el-tag size="mini" :type="s.row.status === '0' ? 'success' : 'danger'">
            {{ s.row.status === '0' ? '成功' : '失败' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="登录方式" width="110">
        <template slot-scope="s">
          <span class="cell-tag">{{ loginTypeLabel(s.row.loginType) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="IP" width="150" show-overflow-tooltip>
        <template slot-scope="s">
          <div>{{ s.row.ip || '-' }}</div>
          <div class="cell-sub">{{ s.row.loginLocation || '地点未知' }}</div>
        </template>
      </el-table-column>
      <el-table-column label="客户端" min-width="180" show-overflow-tooltip>
        <template slot-scope="s">
          <span>{{ s.row.browser || '-' }}</span>
          <span class="cell-split">/</span>
          <span>{{ s.row.os || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="提示" min-width="200" show-overflow-tooltip>
        <template slot-scope="s">
          <span :class="s.row.status === '0' ? 'msg-ok' : 'msg-err'">{{ s.row.msg || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column v-if="$hasPerm('sync:loginlog:remove')" label="操作" width="80">
        <template slot-scope="s">
          <el-button size="mini" type="text" class="op-danger" @click.stop="onDelete(s.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination style="margin-top:16px" background layout="total, prev, pager, next, sizes"
      :total="page.total" :page-size.sync="query.pageSize" :current-page.sync="query.pageNum"
      :page-sizes="[20, 50, 100]" @current-change="load" @size-change="onSizeChange" />

    <!-- 单条详情 -->
    <el-dialog title="登录详情" :visible.sync="detailVisible" width="720px">
      <div v-if="detail" class="detail">
        <div class="d-row"><span class="d-label">时间</span><span>{{ detail.loginTime || '-' }}</span></div>
        <div class="d-row"><span class="d-label">账号</span><span>{{ detail.userName || '-' }}<span class="d-sub">{{ detail.nickName ? '（' + detail.nickName + '）' : '' }}</span></span></div>
        <div class="d-row"><span class="d-label">状态</span><span>
          <el-tag size="mini" :type="detail.status === '0' ? 'success' : 'danger'">
            {{ detail.status === '0' ? '登录成功' : '登录失败' }}
          </el-tag>
        </span></div>
        <div class="d-row"><span class="d-label">方式</span><span>{{ loginTypeLabel(detail.loginType) }}</span></div>
        <div class="d-row"><span class="d-label">IP</span><span>{{ detail.ip || '-' }}</span></div>
        <div class="d-row"><span class="d-label">地点</span><span>{{ detail.loginLocation || '未知 (内网或未解析到)' }}</span></div>
        <div class="d-row"><span class="d-label">浏览器</span><span>{{ detail.browser || '-' }}</span></div>
        <div class="d-row"><span class="d-label">系统</span><span>{{ detail.os || '-' }}</span></div>
        <div class="d-row"><span class="d-label">提示</span><span>{{ detail.msg || '-' }}</span></div>
        <div class="d-row"><span class="d-label">UA</span><span class="d-ua">{{ detail.userAgent || '-' }}</span></div>
      </div>
      <div slot="footer">
        <el-button size="small" @click="detailVisible = false">关闭</el-button>
      </div>
    </el-dialog>
  </el-card>
</template>

<script>
import { pageLoginLog, deleteLoginLog, clearLoginLog } from '@/api/datamove'

export default {
  data () {
    return {
      timeRange: [],
      query: { status: '', loginType: '', keyword: '', pageNum: 1, pageSize: 20 },
      page: { rows: [], total: 0 },
      loading: false,
      detail: null,
      detailVisible: false
    }
  },
  mounted () {
    this.load()
  },
  methods: {
    cond () {
      return {
        status: this.query.status,
        loginType: this.query.loginType,
        keyword: this.query.keyword,
        beginTime: this.timeRange && this.timeRange[0] ? this.timeRange[0] : '',
        endTime: this.timeRange && this.timeRange[1] ? this.timeRange[1] : ''
      }
    },
    load () {
      this.loading = true
      pageLoginLog(Object.assign({}, this.cond(), { pageNum: this.query.pageNum, pageSize: this.query.pageSize }))
        .then(r => { this.page = r.data || { rows: [], total: 0 } })
        .catch(() => {})
        .finally(() => { this.loading = false })
    },
    onSearch () { this.query.pageNum = 1; this.load() },
    onReset () {
      const size = this.query.pageSize
      this.query = { status: '', loginType: '', keyword: '', pageNum: 1, pageSize: size }
      this.timeRange = []
      this.load()
    },
    onSizeChange () { this.query.pageNum = 1; this.load() },
    onRowClick (row) {
      this.detail = row
      this.detailVisible = true
    },
    onDelete (row) {
      this.$confirm(`删除这条登录记录 (${row.userName || '-'} ${row.loginTime || ''})?`, '确认', { type: 'warning' })
        .then(() => deleteLoginLog(row.id).then(() => { this.$message.success('已删除'); this.load() }))
        .catch(() => {})
    },
    onClear () {
      this.$confirm('清空全部登录日志? 该操作不可恢复。', '危险操作', {
        confirmButtonText: '确认清空', cancelButtonText: '取消', type: 'warning'
      }).then(() => clearLoginLog().then(() => { this.$message.success('已清空'); this.load() }))
        .catch(() => {})
    },
    loginTypeLabel (t) {
      if (t === 'PASSWORD') return '账号密码'
      if (t === 'SMS') return '短信验证码'
      if (t === 'EMAIL') return '邮箱验证码'
      return t || '-'
    },
    rowClass ({ row }) {
      return row.status === '1' ? 'fail-row' : ''
    }
  }
}
</script>

<style scoped>
.head-tip { float: right; font-size: 12px; font-weight: normal; color: #909399 }
.cell-sub { font-size: 11px; color: #909399; line-height: 1.2 }
.cell-split { color: #c0c4cc; margin: 0 6px }
.cell-tag { color: #606266 }
.msg-ok { color: #67c23a }
.msg-err { color: #f56c6c }
.op-danger { color: #F56C6C }

/* 失败行淡红底, 扫一眼就能看出异常登录 */
>>> .fail-row { background-color: #fef0f0 !important }

.detail .d-row { display: flex; margin-bottom: 10px; font-size: 13px; color: #333; align-items: flex-start }
.detail .d-label { width: 76px; flex: none; color: #909399 }
.d-sub { color: #909399; font-size: 12px }
.d-ua { color: #909399; font-size: 12px; word-break: break-all }
</style>
