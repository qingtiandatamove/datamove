<template>
  <div>
    <!-- 概览 -->
    <el-row :gutter="12" class="stat-row">
      <el-col :span="4" v-for="c in statCards" :key="c.label">
        <el-card shadow="hover" class="stat-card" :body-style="{ padding: '14px 16px' }">
          <div class="stat-val" :style="{ color: c.color }">{{ c.value }}</div>
          <div class="stat-label">{{ c.label }}</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 实时监控 -->
    <el-card class="block">
      <div slot="header" class="clearfix">
        <span>实时监控</span>
        <span class="sub">行/秒 · ETA · 当前批次 · 瓶颈库</span>
        <div style="float:right">
          <el-switch v-model="autoRefresh" active-text="自动刷新" @change="onAutoChange" />
          <el-button size="mini" icon="el-icon-refresh" style="margin-left:12px" @click="load">刷新</el-button>
        </div>
      </div>

      <div v-if="!liveTasks.length" class="empty">暂无运行中 / 已暂停的任务</div>
      <el-row :gutter="12" v-else>
        <el-col :span="8" v-for="t in liveTasks" :key="t.taskId">
          <div class="mon" :class="{ paused: t.status === 'PAUSE' }">
            <div class="mon-head">
              <span class="mon-name" :title="t.taskName">{{ t.taskName }}</span>
              <el-tag size="mini" :type="statusType(t.status)">{{ statusName(t.status) }}</el-tag>
              <el-tag size="mini" :type="taskTypeTag(t.taskType)">{{ taskTypeName(t.taskType) }}</el-tag>
            </div>
            <div class="mon-sub" :title="t.tableName">
              {{ t.tableName }} · {{ t.sourceName || '?' }} → {{ t.targetName || '?' }}
            </div>

            <div v-if="t.rowsPerSec === null" class="mon-nodata">
              暂无实时指标（任务未在本进程运行，如服务重启后）
            </div>
            <el-progress
              :percentage="t.progress < 0 ? 0 : t.progress"
              :stroke-width="10"
              :show-text="false"
              :status="t.progress >= 100 ? 'success' : null" />
            <div class="mon-progress">
              <span v-if="t.progress < 0">待估算总量</span>
              <span v-else>{{ t.progress }}%</span>
              <span class="right">
                已同步 {{ num(t.syncRows) }} 行
                <template v-if="t.totalEstimate > 0"> / 共 {{ num(t.totalEstimate) }} 行</template>
              </span>
            </div>

            <div class="mon-grid">
              <div class="cell">
                <div class="k">实时速率</div>
                <div class="v">{{ fmtRate(t.rowsPerSec) }}<i> 行/秒</i></div>
              </div>
              <div class="cell">
                <div class="k">ETA 剩余</div>
                <div class="v">{{ t.etaText || '—' }}</div>
              </div>
              <div class="cell">
                <div class="k">当前批次</div>
                <div class="v">
                  <template v-if="t.currentBatch">#{{ t.currentBatch }}<i> · {{ num(t.currentBatchRows) }} 行</i></template>
                  <template v-else>—</template>
                </div>
              </div>
              <div class="cell">
                <div class="k">瓶颈库</div>
                <div class="v">
                  <el-tag size="mini" :type="bottleneckTag(t.bottleneck)">{{ t.bottleneckText || '—' }}</el-tag>
                </div>
              </div>
            </div>

            <div class="mon-foot">
              <span>
                读 <b>{{ fmtMs(t.readMs) }}</b> / 写 <b>{{ fmtMs(t.writeMs) }}</b>
                <el-tooltip placement="top" content="源库读取耗时 与 目标库写入耗时 的窗口平均值, 用于判断同步的瓶颈在哪一侧; 增量任务对比的是「等待 binlog 事件」与「应用变更」的耗时">
                  <i class="el-icon-question" />
                </el-tooltip>
              </span>
              <span v-if="t.shardCount > 1">分片 <b>{{ t.shardCount }}</b></span>
              <span>平均 {{ fmtRate(t.avgRowsPerSec) }} 行/秒</span>
              <span>失败 {{ num(t.failedRows) }}</span>
              <span v-if="t.costSeconds != null">已运行 {{ fmtDuration(t.costSeconds) }}</span>
            </div>

            <!-- 分片实时监控 -->
            <div v-if="t.shards && t.shards.length" class="shards">
              <div class="shards-title">
                分片监控
                <el-tooltip placement="top" content="每个分片负责一段主键区间, 独立线程并行读写; DONE=该分片区间已同步完, FAILED=该分片异常">
                  <i class="el-icon-question" />
                </el-tooltip>
              </div>
              <div class="shard-row" v-for="s in t.shards" :key="s.shardNo" :class="{ failed: s.state === 'FAILED', done: s.state === 'DONE' }">
                <span class="s-no">S{{ s.shardNo }}</span>
                <span class="s-range" :title="'主键区间 ' + s.rangeLo + ' ~ ' + s.rangeHi">{{ num(s.rangeLo) }} ~ {{ num(s.rangeHi) }}</span>
                <span class="s-cur" :title="'游标当前位置'">→ {{ num(s.currentId) }}</span>
                <span class="s-rows">{{ num(s.rows) }} 行</span>
                <span class="s-rate">{{ fmtRate(s.rowsPerSec) }}/s</span>
                <el-tag size="mini" :type="shardStateTag(s.state)">{{ shardStateName(s.state) }}</el-tag>
              </div>
            </div>

            <div class="mon-actions">
              <el-button size="mini" :disabled="t.status !== 'RUNNING'" @click="onPause(t)">暂停</el-button>
              <el-button size="mini" type="warning" :disabled="t.status !== 'PAUSE'" @click="onResume(t)">继续</el-button>
              <el-button size="mini" type="danger" :disabled="!isStoppable(t.status)" @click="onStop(t)">停止</el-button>
              <el-button size="mini" type="text" @click="goTask">任务管理</el-button>
            </div>
          </div>
        </el-col>
      </el-row>
    </el-card>

    <!-- 全部任务 -->
    <el-card class="block">
      <div slot="header" class="clearfix">
        <span>全部任务</span>
        <span class="sub">共 {{ tasks.length }} 个</span>
      </div>
      <el-table :data="tasks" v-loading="loading" border size="small">
        <el-table-column prop="taskName" label="任务名称" min-width="160" show-overflow-tooltip />
        <el-table-column label="类型" width="90">
          <template slot-scope="s">
            <el-tag size="mini" :type="taskTypeTag(s.row.taskType)">{{ taskTypeName(s.row.taskType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="tableName" label="表名" min-width="150" show-overflow-tooltip />
        <el-table-column label="状态" width="100">
          <template slot-scope="s">
            <el-tag size="mini" :type="statusType(s.row.status)">{{ statusName(s.row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="进度" width="150">
          <template slot-scope="s">
            <el-progress v-if="s.row.progress >= 0" :percentage="s.row.progress" :stroke-width="6" />
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="已同步" width="120" align="right">
          <template slot-scope="s">{{ num(s.row.totalRows) }}</template>
        </el-table-column>
        <el-table-column label="实时速率" width="110" align="right">
          <template slot-scope="s">{{ fmtRate(s.row.rowsPerSec) }}</template>
        </el-table-column>
        <el-table-column label="分片" width="70" align="center">
          <template slot-scope="s">
            <el-tag v-if="s.row.shardCount > 1" size="mini" type="warning" effect="plain">{{ s.row.shardCount }}</el-tag>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="瓶颈" width="100">
          <template slot-scope="s">{{ s.row.bottleneckText || '—' }}</template>
        </el-table-column>
        <el-table-column label="更新时间" width="170">
          <template slot-scope="s">{{ fmtTime(s.row.updateTime) }}</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script>
import { taskDashboard, pauseTask, resumeTask, stopTask } from '@/api/datamove'

export default {
  name: 'SyncDashboard',
  data () {
    return {
      tasks: [],
      loading: false,
      autoRefresh: true,
      timer: null,
      refreshing: false
    }
  },
  computed: {
    // 运行中 / 已暂停的任务进入实时卡片区
    liveTasks () {
      return this.tasks.filter(t => t.status === 'RUNNING' || t.status === 'PAUSE')
    },
    statCards () {
      const count = s => this.tasks.filter(t => t.status === s).length
      const running = this.tasks.filter(t => t.status === 'RUNNING')
      const rows = this.tasks.reduce((sum, t) => sum + (Number(t.totalRows) || 0), 0)
      const throughput = running.reduce((sum, t) => sum + (Number(t.rowsPerSec) || 0), 0)
      return [
        { label: '运行中', value: count('RUNNING'), color: '#67C23A' },
        { label: '已暂停', value: count('PAUSE'), color: '#E6A23C' },
        { label: '已完成', value: count('COMPLETED'), color: '#409EFF' },
        { label: '失败', value: count('FAILED'), color: '#F56C6C' },
        { label: '累计同步行数', value: rows.toLocaleString('en-US'), color: '#303133' },
        { label: '实时总吞吐(行/秒)', value: throughput >= 100 ? Math.round(throughput).toLocaleString('en-US') : throughput.toFixed(1), color: '#409EFF' }
      ]
    }
  },
  mounted () {
    this.load()
    this.schedule()
  },
  beforeDestroy () {
    this.clearTimer()
  },
  methods: {
    /**
     * 轮询: 有任务在跑时 3s 一次, 否则 10s 一次兜底
     * (递归 setTimeout 而不是 setInterval, 避免慢请求堆积)
     */
    schedule () {
      this.clearTimer()
      if (!this.autoRefresh) return
      const fast = this.liveTasks.some(t => t.status === 'RUNNING')
      this.timer = setTimeout(() => { this.load(); this.schedule() }, fast ? 3000 : 10000)
    },
    clearTimer () {
      if (this.timer) { clearTimeout(this.timer); this.timer = null }
    },
    onAutoChange (v) {
      if (v) this.schedule()
      else this.clearTimer()
    },
    load () {
      if (this.refreshing) return
      this.refreshing = true
      this.loading = true
      taskDashboard().then(r => {
        this.tasks = (r && r.data) || []
      }).catch(() => {}).finally(() => {
        this.loading = false
        this.refreshing = false
      })
    },
    onPause (t)  { pauseTask(t.taskId).then(() => { this.$message.success('已暂停'); this.load() }).catch(() => {}) },
    onResume (t) { resumeTask(t.taskId).then(() => { this.$message.success('已继续'); this.load() }).catch(() => {}) },
    onStop (t)   { stopTask(t.taskId).then(() => { this.$message.success('已停止'); this.load() }).catch(() => {}) },
    goTask () { this.$router.push('/sync/task') },
    isStoppable (s) { return s === 'RUNNING' || s === 'PAUSE' },

    /* ---------- 展示格式化 ---------- */
    num (v) {
      if (v === null || v === undefined) return '—'
      return Number(v).toLocaleString('en-US')
    },
    fmtRate (v) {
      if (v === null || v === undefined) return '—'
      const n = Number(v)
      return n >= 100 ? Math.round(n).toLocaleString('en-US') : n.toFixed(1)
    },
    fmtMs (v) {
      if (v === null || v === undefined) return '—'
      return Math.round(Number(v)) + 'ms'
    },
    fmtDuration (sec) {
      const s = Number(sec) || 0
      if (s < 60) return s + 's'
      const h = Math.floor(s / 3600)
      const m = Math.floor((s % 3600) / 60)
      return h > 0 ? `${h}h${m}m` : `${m}m${s % 60}s`
    },
    fmtTime (t) {
      if (!t) return '-'
      const d = new Date(t)
      if (isNaN(d.getTime())) return '-'
      const p = n => String(n).padStart(2, '0')
      return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
    },
    statusName (s) { return ({ STOP: '未启动', RUNNING: '运行中', PAUSE: '已暂停', COMPLETED: '已完成', FAILED: '失败' })[s] || s },
    statusType (s) { return ({ STOP: 'info', RUNNING: 'success', PAUSE: 'warning', COMPLETED: '', FAILED: 'danger' })[s] || '' },
    taskTypeName (t) { return ({ FULL: '全量', INCR: '增量', DDL: '表结构' })[t] || t },
    taskTypeTag (t) { return ({ FULL: '', INCR: 'success', DDL: 'warning' })[t] || '' },
    bottleneckTag (b) { return ({ SOURCE: 'warning', TARGET: 'danger', BALANCED: 'success' })[b] || 'info' },
    shardStateName (s) { return ({ RUNNING: '同步中', DONE: '已完成', FAILED: '失败' })[s] || s },
    shardStateTag (s) { return ({ RUNNING: 'primary', DONE: 'success', FAILED: 'danger' })[s] || 'info' }
  }
}
</script>

<style scoped>
.stat-row { margin-bottom: 12px }
.stat-card { text-align: center }
.stat-val { font-size: 22px; font-weight: 600; line-height: 1.2 }
.stat-label { color: #909399; font-size: 12px; margin-top: 6px }
.block { margin-bottom: 12px }
.sub { color: #909399; font-size: 12px; margin-left: 8px }
.empty { color: #909399; font-size: 13px; text-align: center; padding: 24px 0 }
.muted { color: #c0c4cc }

.mon {
  border: 1px solid #ebeef5; border-radius: 4px;
  padding: 12px; margin-bottom: 12px; background: #fff;
}
.mon.paused { background: #fdf6ec; border-color: #f5dab1 }
.mon-head { display: flex; align-items: center; gap: 6px; margin-bottom: 4px }
.mon-name {
  font-weight: 600; font-size: 14px; color: #303133;
  max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.mon-sub {
  color: #909399; font-size: 12px; margin-bottom: 8px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.mon-progress {
  display: flex; justify-content: space-between;
  color: #606266; font-size: 12px; margin: 2px 0 10px;
}
.mon-nodata { color: #E6A23C; font-size: 12px; margin-bottom: 6px }
.mon-progress .right { color: #909399 }
.mon-grid {
  display: grid; grid-template-columns: 1fr 1fr;
  gap: 8px 12px; padding: 8px 0; border-top: 1px dashed #ebeef5;
}
.mon-grid .k { color: #909399; font-size: 12px }
.mon-grid .v { color: #303133; font-size: 15px; font-weight: 600; font-family: Menlo, Consolas, monospace }
.mon-grid .v i { font-size: 12px; font-weight: 400; color: #909399; font-style: normal }
.mon-foot {
  display: flex; flex-wrap: wrap; gap: 12px;
  border-top: 1px dashed #ebeef5; padding-top: 8px;
  color: #909399; font-size: 12px;
}
.mon-foot b { color: #606266; font-weight: 600 }
.mon-actions { margin-top: 10px; text-align: right }

/* 分片实时监控 */
.shards {
  margin-top: 10px; padding-top: 8px;
  border-top: 1px dashed #ebeef5;
}
.shards-title {
  color: #909399; font-size: 12px; margin-bottom: 6px;
  display: flex; align-items: center; gap: 4px;
}
.shard-row {
  display: flex; align-items: center; gap: 10px;
  font-size: 12px; color: #606266;
  padding: 3px 8px; border-radius: 3px;
  font-family: Menlo, Consolas, monospace;
}
.shard-row:nth-child(even) { background: #fafafa }
.shard-row.failed { background: #fef0f0 }
.shard-row.done { opacity: 0.65 }
.shard-row .s-no {
  flex: none; width: 28px; font-weight: 600; color: #409EFF;
}
.shard-row .s-range {
  flex: 1; min-width: 0;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.shard-row .s-cur { flex: none; color: #909399 }
.shard-row .s-rows { flex: none; min-width: 90px; text-align: right }
.shard-row .s-rate { flex: none; min-width: 70px; text-align: right; color: #303133; font-weight: 600 }
.shard-row .el-tag { flex: none; margin-left: auto }
</style>
