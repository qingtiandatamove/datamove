<template>
  <el-card>
    <div slot="header">
      <el-form :inline="true" :model="query">
        <el-form-item><el-input v-model="query.taskId" placeholder="任务ID" size="small" clearable /></el-form-item>
        <el-form-item>
          <el-select v-model="query.status" size="small" clearable placeholder="状态" style="width:120px">
            <el-option label="成功" value="SUCCESS" />
            <el-option label="失败" value="FAILED" />
            <el-option label="运行中" value="RUNNING" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" size="small" icon="el-icon-search" @click="load">查询</el-button>
          <el-button size="small" icon="el-icon-refresh" @click="onReset">重置</el-button>
          <el-button type="success" size="small" icon="el-icon-download" @click="onExport">导出CSV</el-button>
        </el-form-item>
      </el-form>
    </div>

    <el-table :data="page.rows" v-loading="loading" border>
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="taskId" label="任务ID" width="80" />
      <el-table-column prop="taskName" label="任务名称" />
      <el-table-column prop="tableName" label="表" />
      <el-table-column prop="batchNo" label="批次" width="60" />
      <el-table-column prop="batchStartId" label="起" width="130" show-overflow-tooltip />
      <el-table-column prop="batchEndId" label="止" width="130" show-overflow-tooltip />
      <el-table-column prop="batchRows" label="行数" width="80" />
      <el-table-column prop="totalRows" label="累计" width="100" />
      <el-table-column prop="costMs" label="耗时(ms)" width="110" />
      <el-table-column prop="status" label="状态" width="80">
        <template slot-scope="s">
          <el-tag size="mini" :type="s.row.status === 'SUCCESS' ? 'success' : (s.row.status === 'FAILED' ? 'danger' : '')">
            {{ s.row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="同步内容" min-width="220">
        <template slot-scope="s">
          <span v-if="s.row.content" class="content-cell" @click="showDetail(s.row)">{{ oneLine(s.row.content) }}</span>
          <span v-else class="empty-cell">-</span>
        </template>
      </el-table-column>
      <el-table-column prop="errorMsg" label="异常信息" show-overflow-tooltip />
      <el-table-column label="操作" width="80" fixed="right">
        <template slot-scope="s">
          <el-button size="mini" @click="showDetail(s.row)">详情</el-button>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="时间" width="160" />
    </el-table>
    <el-pagination
      style="margin-top:16px" background layout="prev, pager, next, total"
      :total="page.total" :page-size="query.pageSize" :current-page.sync="query.pageNum" @current-change="load" />

    <!-- 批次详情: 展示本批次同步的数据内容 -->
    <el-dialog title="同步批次详情" :visible.sync="detailVisible" width="820px">
      <div v-if="detail" class="detail">
        <div class="d-row"><span class="d-label">时间</span><span>{{ detail.createTime }}</span></div>
        <div class="d-row"><span class="d-label">任务</span><span>{{ detail.taskName }} (ID {{ detail.taskId }})</span></div>
        <div class="d-row"><span class="d-label">表 / 模式</span><span>{{ detail.tableName }} · {{ detail.syncMode }}</span></div>
        <div class="d-row">
          <span class="d-label">执行结果</span>
          <span>
            <el-tag size="mini" :type="detail.status === 'SUCCESS' ? 'success' : 'danger'">{{ detail.status }}</el-tag>
            <span class="d-meta">第 {{ detail.batchNo }} 批 · 本批 {{ detail.batchRows }} 行 · 累计 {{ detail.totalRows }} 行 · 耗时 {{ detail.costMs }}ms</span>
          </span>
        </div>
        <div class="d-row"><span class="d-label">位点</span><span class="d-ua">{{ detail.batchStartId }} ~ {{ detail.batchEndId }}</span></div>
        <div class="d-row">
          <span class="d-label">同步内容</span>
          <pre class="content-pre">{{ detail.content || '无(该批次无数据变更)' }}</pre>
        </div>
        <div v-if="detail.errorMsg" class="d-row">
          <span class="d-label">异常信息</span>
          <pre class="err-pre">{{ detail.errorMsg }}</pre>
        </div>
      </div>
      <div slot="footer">
        <el-button size="small" @click="detailVisible = false">关闭</el-button>
      </div>
    </el-dialog>
  </el-card>
</template>

<script>
import { pageLog, exportLogUrl } from '@/api/datamove'

export default {
  data () {
    return {
      query: { taskId: '', status: '', pageNum: 1, pageSize: 20 },
      page: { rows: [], total: 0 },
      loading: false,
      detail: null,
      detailVisible: false
    }
  },
  mounted () { this.load() },
  methods: {
    load () {
      this.loading = true
      pageLog(this.query).then(r => { this.page = r.data }).catch(() => {}).finally(() => this.loading = false)
    },
    onExport () {
      window.open(exportLogUrl({ taskId: this.query.taskId, status: this.query.status }))
    },
    onReset () {
      const size = this.query.pageSize
      this.query = { taskId: '', status: '', pageNum: 1, pageSize: size }
      this.load()
    },
    /* 列表里内容压成一行展示 */
    oneLine (text) {
      if (!text) return ''
      const one = text.replace(/\s+/g, ' ').trim()
      return one.length > 60 ? one.slice(0, 60) + '...' : one
    },
    showDetail (row) {
      this.detail = row
      this.detailVisible = true
    }
  }
}
</script>

<style scoped>
.content-cell { cursor: pointer; color: #1890ff; font-family: Consolas, Menlo, monospace; font-size: 12px }
.empty-cell { color: #c0c4cc }
.detail .d-row { display: flex; margin-bottom: 10px; font-size: 13px; color: #333 }
.detail .d-label { width: 76px; flex: none; color: #909399 }
.d-meta { margin-left: 8px; color: #606266; font-size: 12px }
.d-ua { color: #909399; font-size: 12px; word-break: break-all }
.content-pre {
  flex: 1; margin: 0; padding: 10px; max-height: 360px; overflow: auto;
  background: #f5f7fa; border: 1px solid #ebeef5; border-radius: 4px;
  font-family: Consolas, Menlo, monospace; font-size: 12px; white-space: pre-wrap; word-break: break-all;
}
.err-pre {
  flex: 1; margin: 0; padding: 10px; max-height: 160px; overflow: auto;
  background: #fef0f0; border: 1px solid #fde2e2; border-radius: 4px; color: #f56c6c;
  font-family: Consolas, Menlo, monospace; font-size: 12px; white-space: pre-wrap; word-break: break-all;
}
</style>
