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

    <!-- 运行历史 -->
    <el-card class="block">
      <div slot="header" class="clearfix">
        <span>运行历史</span>
        <span class="sub">每次「启动」任务一条记录 · 结果 / 耗时 / 行数 / 速率 / 异常</span>
        <div style="float:right">
          <el-button size="mini" icon="el-icon-refresh" @click="refreshRunAll">刷新</el-button>
          <el-button size="mini" icon="el-icon-download" @click="onRunExport">导出 CSV</el-button>
          <el-button size="mini" type="danger" icon="el-icon-delete" @click="openRunClear">清理</el-button>
        </div>
      </div>

      <!-- 趋势 -->
      <div class="run-chart-head">
        <span>近
          <el-select v-model="runTrendDays" size="mini" style="width:82px" @change="loadRunTrend">
            <el-option :value="7" label="7 天" />
            <el-option :value="14" label="14 天" />
            <el-option :value="30" label="30 天" />
          </el-select>
          运行趋势
        </span>
        <span class="sub">柱=运行次数(成功/失败) · 线=同步行数</span>
      </div>
      <div ref="runChart" v-loading="runTrendLoading" class="run-chart" />

      <!-- 概览 -->
      <el-row :gutter="10" class="run-stat">
        <el-col :span="3" v-for="c in runStatCards" :key="c.label">
          <div class="run-cell">
            <div class="run-val" :style="{ color: c.color }">{{ c.value }}</div>
            <div class="run-label">{{ c.label }}</div>
          </div>
        </el-col>
      </el-row>

      <!-- 筛选 -->
      <el-form :inline="true" size="mini" class="run-filter" @submit.native.prevent>
        <el-form-item>
          <el-select v-model="runQuery.taskId" placeholder="任务" clearable filterable style="width:170px">
            <el-option v-for="t in tasks" :key="t.taskId" :label="t.taskName" :value="t.taskId" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-input v-model="runQuery.tableName" placeholder="表名" clearable style="width:140px" @keyup.enter.native="onRunSearch" />
        </el-form-item>
        <el-form-item>
          <el-select v-model="runQuery.taskType" placeholder="类型" clearable style="width:100px">
            <el-option label="全量" value="FULL" />
            <el-option label="增量" value="INCR" />
            <el-option label="表结构" value="DDL" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-select v-model="runQuery.status" placeholder="结果" clearable style="width:110px">
            <el-option label="运行中" value="RUNNING" />
            <el-option label="已完成" value="COMPLETED" />
            <el-option label="已暂停" value="PAUSE" />
            <el-option label="失败" value="FAILED" />
            <el-option label="已停止" value="STOP" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-date-picker
            v-model="runTimeRange"
            type="datetimerange"
            value-format="yyyy-MM-dd HH:mm:ss"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            :default-time="['00:00:00', '23:59:59']"
            style="width:340px" />
        </el-form-item>
        <el-form-item>
          <el-input v-model="runQuery.keyword" placeholder="关键字(库名/异常)" clearable style="width:160px" @keyup.enter.native="onRunSearch" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="onRunSearch">查询</el-button>
          <el-button @click="onRunReset">重置</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="runs" v-loading="runLoading" border size="small" :row-class-name="runRowClass" @row-dblclick="openRunDetail">
        <el-table-column prop="id" label="运行ID" width="80" align="center" />
        <el-table-column label="任务" min-width="160" show-overflow-tooltip>
          <template slot-scope="s">
            {{ s.row.taskName }}
            <el-tag size="mini" :type="taskTypeTag(s.row.taskType)">{{ taskTypeName(s.row.taskType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="syncMode" label="模式" width="80" align="center" />
        <el-table-column prop="tableName" label="表名" min-width="130" show-overflow-tooltip />
        <el-table-column label="结果" width="90" align="center">
          <template slot-scope="s">
            <el-tag size="mini" :type="statusType(s.row.status)">{{ statusName(s.row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="开始时间" width="150">
          <template slot-scope="s">{{ fmtTime(s.row.startTime) }}</template>
        </el-table-column>
        <el-table-column label="结束时间" width="150">
          <template slot-scope="s">{{ fmtTime(s.row.endTime) }}</template>
        </el-table-column>
        <el-table-column label="耗时" width="90" align="right">
          <template slot-scope="s">{{ s.row.costSeconds == null ? '—' : fmtDuration(s.row.costSeconds) }}</template>
        </el-table-column>
        <el-table-column label="成功行数" width="100" align="right">
          <template slot-scope="s">{{ num(s.row.successRows) }}</template>
        </el-table-column>
        <el-table-column label="失败行数" width="90" align="right">
          <template slot-scope="s">
            <span :class="{ danger: Number(s.row.failedRows) > 0 }">{{ num(s.row.failedRows) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="平均速率" width="100" align="right">
          <template slot-scope="s">{{ fmtRate(s.row.avgRowsPerSec) }}</template>
        </el-table-column>
        <el-table-column label="批次" width="70" align="right">
          <template slot-scope="s">{{ num(s.row.batchCount) }}</template>
        </el-table-column>
        <el-table-column label="分片" width="60" align="center">
          <template slot-scope="s">{{ s.row.shardCount > 1 ? s.row.shardCount : '—' }}</template>
        </el-table-column>
        <el-table-column label="异常" width="70" align="center">
          <template slot-scope="s">
            <el-tooltip v-if="s.row.errorMsg" placement="top" :content="s.row.errorMsg">
              <el-tag size="mini" type="danger">有</el-tag>
            </el-tooltip>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="70" align="center">
          <template slot-scope="s">
            <el-button type="text" size="mini" @click="openRunDetail(s.row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="run-page"
        background
        layout="total, sizes, prev, pager, next"
        :total="runTotal"
        :current-page.sync="runQuery.pageNum"
        :page-size="runQuery.pageSize"
        :page-sizes="[10, 20, 50, 100]"
        @current-change="onRunPageChange"
        @size-change="onRunSizeChange" />
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
        <el-table-column label="运行次数" width="90" align="center">
          <template slot-scope="s">
            <el-button v-if="s.row.runCount" type="text" size="mini" @click="filterRunByTask(s.row)">{{ s.row.runCount }}</el-button>
            <span v-else class="muted">0</span>
          </template>
        </el-table-column>
        <el-table-column label="最近运行" width="190">
          <template slot-scope="s">
            <template v-if="s.row.lastRunTime">
              <el-tag size="mini" :type="statusType(s.row.lastRunStatus)">{{ statusName(s.row.lastRunStatus) }}</el-tag>
              <span class="last-run">{{ fmtTime(s.row.lastRunTime) }}</span>
              <div class="last-run-sub">
                {{ num(s.row.lastRunRows) }} 行 · {{ s.row.lastRunCostSeconds == null ? '—' : fmtDuration(s.row.lastRunCostSeconds) }}
              </div>
            </template>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="170">
          <template slot-scope="s">{{ fmtTime(s.row.updateTime) }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 清理运行历史 -->
    <el-dialog title="清理运行历史" :visible.sync="clearVisible" width="470px" append-to-body>
      <div class="clear-tip">按条件清理运行历史; 「运行中」的记录不会被清理。</div>
      <el-form label-width="100px" size="mini">
        <el-form-item label="清理范围">
          <el-radio-group v-model="clearScope">
            <el-radio label="filter">当前筛选条件 ({{ runTotal }} 条)</el-radio>
            <el-radio label="days">只保留最近 N 天</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="clearScope === 'days'" label="保留天数">
          <el-input-number v-model="clearDays" :min="1" :max="3650" size="mini" />
          <span class="clear-tip">早于该范围的运行历史会被删除</span>
        </el-form-item>
        <el-form-item label="清空全部">
          <el-checkbox v-model="clearForce">不带任何条件时, 确认清空全部运行历史</el-checkbox>
        </el-form-item>
      </el-form>
      <div slot="footer">
        <el-button size="mini" @click="clearVisible = false">取消</el-button>
        <el-button size="mini" type="danger" :loading="clearing" @click="doRunClear">确定清理</el-button>
      </div>
    </el-dialog>

    <!-- 运行详情 -->
    <el-dialog title="运行详情" :visible.sync="detailVisible" width="640px" append-to-body>
      <div v-if="detail" class="d-wrap">
        <div class="d-row"><span class="d-label">运行ID</span><span>{{ detail.id }}</span></div>
        <div class="d-row"><span class="d-label">任务</span><span>{{ detail.taskName }} (ID {{ detail.taskId }})</span></div>
        <div class="d-row"><span class="d-label">类型 / 模式</span><span>{{ taskTypeName(detail.taskType) }} · {{ modeName(detail.syncMode) }}</span></div>
        <div class="d-row"><span class="d-label">表名</span><span>{{ detail.tableName }}</span></div>
        <div class="d-row"><span class="d-label">数据源</span><span>{{ detail.sourceName || '?' }} → {{ detail.targetName || '?' }}</span></div>
        <div class="d-row"><span class="d-label">结果</span>
          <span><el-tag size="mini" :type="statusType(detail.status)">{{ statusName(detail.status) }}</el-tag></span>
        </div>
        <div class="d-row"><span class="d-label">开始 / 结束</span>
          <span>{{ fmtTime(detail.startTime) }} → {{ detail.endTime ? fmtTime(detail.endTime) : '—' }}</span>
        </div>
        <div class="d-row"><span class="d-label">耗时</span>
          <span>{{ detail.costSeconds == null ? '—' : fmtDuration(detail.costSeconds) }}</span>
        </div>
        <div class="d-row"><span class="d-label">行数</span>
          <span>成功 {{ num(detail.successRows) }} · 失败 {{ num(detail.failedRows) }} · 合计 {{ num(detail.totalRows) }}</span>
        </div>
        <div class="d-row"><span class="d-label">速率 / 批次</span>
          <span>{{ fmtRate(detail.avgRowsPerSec) }} 行/秒 · {{ num(detail.batchCount) }} 批 · {{ detail.shardCount }} 分片</span>
        </div>
        <div v-if="detail.errorMsg" class="d-row">
          <span class="d-label">异常信息</span>
          <pre class="err-pre">{{ detail.errorMsg }}</pre>
        </div>
      </div>
      <div slot="footer">
        <el-button size="small" @click="copyRunDetail">复制详情</el-button>
        <el-button size="small" type="primary" @click="detailVisible = false">关闭</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import * as echarts from 'echarts/core'
import { LineChart, BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
echarts.use([LineChart, BarChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

import {
  taskDashboard, pauseTask, resumeTask, stopTask,
  runPage, runSummary, runTrend, clearTaskRun, exportRunUrl
} from '@/api/datamove'

const EMPTY_RUN_SUMMARY = {
  total: 0, completedCount: 0, failedCount: 0, runningCount: 0, pausedCount: 0,
  stoppedCount: 0, rowsSum: 0, failedRowsSum: 0, avgCostSeconds: 0, maxCostSeconds: 0,
  avgRowsPerSec: 0, errorCount: 0
}

export default {
  name: 'SyncDashboard',
  data () {
    return {
      tasks: [],
      loading: false,
      autoRefresh: true,
      timer: null,
      refreshing: false,

      /* ---------- 运行历史 ---------- */
      runs: [],
      runLoading: false,
      runTotal: 0,
      runSummary: Object.assign({}, EMPTY_RUN_SUMMARY),
      runTrendDays: 14,
      runTrendLoading: false,
      runTimeRange: [],
      runQuery: {
        pageNum: 1, pageSize: 20, taskId: null, taskName: null, tableName: null,
        taskType: null, status: null, keyword: null
      },
      lastRunFlush: 0,
      clearVisible: false,
      clearScope: 'days',
      clearDays: 30,
      clearForce: false,
      clearing: false,
      detailVisible: false,
      detail: null
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
    },
    // 运行历史概览 (按当前筛选条件)
    runStatCards () {
      const s = this.runSummary || {}
      const n = v => Number(v) || 0
      return [
        { label: '运行次数', value: n(s.total), color: '#303133' },
        { label: '成功', value: n(s.completedCount), color: '#67C23A' },
        { label: '失败', value: n(s.failedCount), color: '#F56C6C' },
        { label: '运行中', value: n(s.runningCount), color: '#409EFF' },
        { label: '同步行数', value: n(s.rowsSum).toLocaleString('en-US'), color: '#303133' },
        { label: '失败行数', value: n(s.failedRowsSum).toLocaleString('en-US'), color: n(s.failedRowsSum) > 0 ? '#F56C6C' : '#303133' },
        { label: '平均耗时', value: this.fmtDuration(n(s.avgCostSeconds)), color: '#909399' },
        { label: '平均速率(行/秒)', value: this.fmtRate(n(s.avgRowsPerSec)), color: '#409EFF' }
      ]
    }
  },
  mounted () {
    this.load()
    this.schedule()
    this.refreshRunAll()
    window.addEventListener('resize', this.resizeRunChart)
  },
  beforeDestroy () {
    this.clearTimer()
    window.removeEventListener('resize', this.resizeRunChart)
    if (this.runChart) { this.runChart.dispose(); this.runChart = null }
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
        this.maybeRefreshRuns()
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

    /* ---------- 运行历史 ---------- */
    refreshRunAll () {
      this.loadRun()
      this.loadRunSummary()
      this.loadRunTrend()
    },
    // 跟大盘一起轮询: 有运行中的任务时最多 10s 静默刷新一次, 避免频繁查历史表
    maybeRefreshRuns () {
      if (!this.autoRefresh) return
      if (Date.now() - this.lastRunFlush < 10000) return
      this.loadRun()
    },
    runFilterParams (withPage) {
      const q = this.runQuery
      const p = {
        taskId: q.taskId || undefined,
        taskName: q.taskName || undefined,
        tableName: q.tableName || undefined,
        taskType: q.taskType || undefined,
        status: q.status || undefined,
        keyword: q.keyword || undefined,
        beginTime: this.runTimeRange && this.runTimeRange[0] ? this.runTimeRange[0] : undefined,
        endTime: this.runTimeRange && this.runTimeRange[1] ? this.runTimeRange[1] : undefined
      }
      if (withPage) { p.pageNum = q.pageNum; p.pageSize = q.pageSize }
      return p
    },
    loadRun () {
      this.lastRunFlush = Date.now()
      this.runLoading = true
      runPage(this.runFilterParams(true)).then(r => {
        const d = (r && r.data) || {}
        this.runs = d.rows || []
        this.runTotal = Number(d.total) || 0
      }).catch(() => {}).finally(() => { this.runLoading = false })
    },
    loadRunSummary () {
      runSummary(this.runFilterParams(false)).then(r => {
        this.runSummary = Object.assign({}, EMPTY_RUN_SUMMARY, (r && r.data) || {})
      }).catch(() => {})
    },
    loadRunTrend () {
      this.runTrendLoading = true
      runTrend({ days: this.runTrendDays }).then(r => {
        this.renderRunChart((r && r.data) || [])
      }).catch(() => {}).finally(() => { this.runTrendLoading = false })
    },
    onRunSearch () {
      this.runQuery.pageNum = 1
      this.loadRun()
      this.loadRunSummary()
    },
    onRunReset () {
      this.runTimeRange = []
      this.runQuery = {
        pageNum: 1, pageSize: this.runQuery.pageSize, taskId: null, taskName: null,
        tableName: null, taskType: null, status: null, keyword: null
      }
      this.loadRun()
      this.loadRunSummary()
    },
    onRunPageChange (page) {
      if (page) this.runQuery.pageNum = page
      this.loadRun()
    },
    onRunSizeChange (size) {
      this.runQuery.pageSize = size
      this.runQuery.pageNum = 1
      this.loadRun()
    },
    // 全部任务表格点「运行次数」→ 只看该任务的运行历史
    filterRunByTask (row) {
      this.runQuery.taskId = row.taskId
      this.runQuery.taskName = null
      this.runQuery.pageNum = 1
      this.loadRun()
      this.loadRunSummary()
    },
    onRunExport () { window.open(exportRunUrl(this.runFilterParams(false))) },
    openRunClear () {
      if (!this.clearDays) this.clearDays = 30
      this.clearVisible = true
    },
    doRunClear () {
      const p = this.clearScope === 'days' ? { beforeDays: this.clearDays } : this.runFilterParams(false)
      if (this.clearForce) p.force = true
      this.clearing = true
      clearTaskRun(p).then(r => {
        this.$message.success((r && r.msg) || '已清理')
        this.clearVisible = false
        this.refreshRunAll()
      }).catch(() => {}).finally(() => { this.clearing = false })
    },
    openRunDetail (row) {
      this.detail = row
      this.detailVisible = true
    },
    copyRunDetail () {
      const d = this.detail || {}
      const text = [
        `运行ID: ${d.id}`,
        `任务: ${d.taskName} (ID ${d.taskId})`,
        `类型 / 模式: ${this.taskTypeName(d.taskType)} · ${this.modeName(d.syncMode)}`,
        `表名: ${d.tableName}`,
        `数据源: ${d.sourceName || '?'} -> ${d.targetName || '?'}`,
        `结果: ${this.statusName(d.status)}`,
        `开始: ${this.fmtTime(d.startTime)}  结束: ${d.endTime ? this.fmtTime(d.endTime) : '—'}`,
        `耗时: ${d.costSeconds == null ? '—' : this.fmtDuration(d.costSeconds)}`,
        `行数: 成功 ${d.successRows || 0} / 失败 ${d.failedRows || 0} / 合计 ${d.totalRows || 0}`,
        `速率: ${d.avgRowsPerSec || 0} 行/秒 · ${d.batchCount || 0} 批 · ${d.shardCount || 1} 分片`,
        d.errorMsg ? `异常信息: ${d.errorMsg}` : ''
      ].filter(Boolean).join('\n')
      const done = () => this.$message.success('已复制到剪贴板')
      if (navigator.clipboard) {
        navigator.clipboard.writeText(text).then(done).catch(() => this.fallbackCopy(text, done))
      } else {
        this.fallbackCopy(text, done)
      }
    },
    fallbackCopy (text, done) {
      const ta = document.createElement('textarea')
      ta.value = text
      document.body.appendChild(ta)
      ta.select()
      try { document.execCommand('copy'); done() } catch (e) { this.$message.warning('复制失败, 请手动选择') }
      document.body.removeChild(ta)
    },
    runRowClass ({ row }) {
      if (row.status === 'FAILED') return 'failed-row'
      if (row.status === 'RUNNING') return 'running-row'
      return ''
    },
    modeName (m) {
      return ({ ID: '按主键', TIME: '按时间', BINLOG: '增量binlog', FULL: '全量', INCR: '增量', DDL: '表结构' })[m] || m || '-'
    },
    renderRunChart (data) {
      if (!this.$refs.runChart || !data.length) return
      if (!this.runChart) this.runChart = echarts.init(this.$refs.runChart)
      const dates = data.map(d => String(d.date).slice(5))
      const done = data.map(d => Number(d.completedCount) || 0)
      const fail = data.map(d => Number(d.failedCount) || 0)
      const rows = data.map(d => Number(d.rows) || 0)
      this.runChart.setOption({
        tooltip: { trigger: 'axis' },
        legend: { data: ['成功', '失败', '同步行数'], right: 10, top: 0, itemWidth: 12, itemHeight: 8, textStyle: { fontSize: 11 } },
        grid: { left: 48, right: 62, top: 30, bottom: 24 },
        xAxis: { type: 'category', data: dates, axisLabel: { fontSize: 10 } },
        yAxis: [
          { type: 'value', name: '次数', minInterval: 1, nameTextStyle: { fontSize: 10 }, axisLabel: { fontSize: 10 } },
          { type: 'value', name: '行数', nameTextStyle: { fontSize: 10 }, axisLabel: { fontSize: 10 }, splitLine: { show: false } }
        ],
        series: [
          { name: '成功', type: 'bar', stack: 'run', barMaxWidth: 16, itemStyle: { color: '#67C23A' }, data: done },
          { name: '失败', type: 'bar', stack: 'run', barMaxWidth: 16, itemStyle: { color: '#F56C6C' }, data: fail },
          { name: '同步行数', type: 'line', yAxisIndex: 1, smooth: true, symbolSize: 5, itemStyle: { color: '#409EFF' }, data: rows }
        ]
      })
      this.runChart.resize()
    },
    resizeRunChart () { if (this.runChart) this.runChart.resize() },

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
.stat-card { text-align: center; background: var(--bg-card) }
.stat-val { font-size: 22px; font-weight: 600; line-height: 1.2 }
.stat-label { color: var(--color-text-secondary); font-size: 12px; margin-top: 6px }
.block { margin-bottom: 12px }
.sub { color: var(--color-text-secondary); font-size: 12px; margin-left: 8px }
.empty { color: var(--color-text-secondary); font-size: 13px; text-align: center; padding: 24px 0 }
.muted { color: var(--color-text-placeholder) }

.mon {
  border: 1px solid var(--color-border); border-radius: 4px;
  padding: 12px; margin-bottom: 12px; background: var(--bg-card);
}
.mon.paused { background: rgba(230, 162, 60, .12); border-color: rgba(230, 162, 60, .3) }
.mon-head { display: flex; align-items: center; gap: 6px; margin-bottom: 4px }
.mon-name {
  font-weight: 600; font-size: 14px; color: var(--color-text-primary);
  max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.mon-sub {
  color: var(--color-text-secondary); font-size: 12px; margin-bottom: 8px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.mon-progress {
  display: flex; justify-content: space-between;
  color: var(--color-text-regular); font-size: 12px; margin: 2px 0 10px;
}
.mon-nodata { color: var(--color-warning); font-size: 12px; margin-bottom: 6px }
.mon-progress .right { color: var(--color-text-secondary) }
.mon-grid {
  display: grid; grid-template-columns: 1fr 1fr;
  gap: 8px 12px; padding: 8px 0; border-top: 1px dashed var(--color-border);
}
.mon-grid .k { color: var(--color-text-secondary); font-size: 12px }
.mon-grid .v { color: var(--color-text-primary); font-size: 15px; font-weight: 600; font-family: Menlo, Consolas, monospace }
.mon-grid .v i { font-size: 12px; font-weight: 400; color: var(--color-text-secondary); font-style: normal }
.mon-foot {
  display: flex; flex-wrap: wrap; gap: 12px;
  border-top: 1px dashed var(--color-border); padding-top: 8px;
  color: var(--color-text-secondary); font-size: 12px;
}
.mon-foot b { color: var(--color-text-regular); font-weight: 600 }
.mon-actions { margin-top: 10px; text-align: right }

/* 分片实时监控 */
.shards {
  margin-top: 10px; padding-top: 8px;
  border-top: 1px dashed var(--color-border);
}
.shards-title {
  color: var(--color-text-secondary); font-size: 12px; margin-bottom: 6px;
  display: flex; align-items: center; gap: 4px;
}
.shard-row {
  display: flex; align-items: center; gap: 10px;
  font-size: 12px; color: var(--color-text-regular);
  padding: 3px 8px; border-radius: 3px;
  font-family: Menlo, Consolas, monospace;
}
.shard-row:nth-child(even) { background: var(--bg-hover) }
.shard-row.failed { background: rgba(245, 108, 108, .12) }
.shard-row.done { opacity: 0.65 }
.shard-row .s-no {
  flex: none; width: 28px; font-weight: 600; color: var(--color-primary);
}
.shard-row .s-range {
  flex: 1; min-width: 0;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.shard-row .s-cur { flex: none; color: var(--color-text-secondary) }
.shard-row .s-rows { flex: none; min-width: 90px; text-align: right }
.shard-row .s-rate { flex: none; min-width: 70px; text-align: right; color: var(--color-text-primary); font-weight: 600 }
.shard-row .el-tag { flex: none; margin-left: auto }

/* 运行历史 */
.run-chart-head {
  color: var(--color-text-regular); font-size: 12px; margin-bottom: 6px;
  display: flex; align-items: center; gap: 8px;
}
.run-chart { height: 180px; width: 100% }
.run-stat { margin: 10px 0 4px }
.run-cell {
  border: 1px solid var(--color-border); border-radius: 4px;
  text-align: center; padding: 8px 4px; background: var(--bg-hover);
}
.run-val { font-size: 17px; font-weight: 600; line-height: 1.2 }
.run-label { color: var(--color-text-secondary); font-size: 12px; margin-top: 4px }
.run-filter { margin-top: 10px; padding-top: 10px; border-top: 1px dashed var(--color-border) }
.run-page { margin-top: 10px; text-align: right }
.last-run { margin-left: 6px; color: var(--color-text-regular); font-size: 12px }
.last-run-sub { color: var(--color-text-secondary); font-size: 12px }
.danger { color: #ff8585; font-weight: 600 }
.clear-tip { color: var(--color-text-secondary); font-size: 12px; margin-left: 6px }

.d-wrap .d-row { display: flex; margin-bottom: 10px; font-size: 13px; color: var(--color-text-primary) }
.d-wrap .d-label { width: 92px; flex: none; color: var(--color-text-secondary) }
.err-pre {
  flex: 1; margin: 0; padding: 10px; max-height: 160px; overflow: auto;
  background: rgba(245, 108, 108, .12); border: 1px solid rgba(245, 108, 108, .25); border-radius: 4px; color: #ff8585;
  font-family: Consolas, Menlo, monospace; font-size: 12px; white-space: pre-wrap; word-break: break-all;
}

/* 失败 / 运行中的历史行高亮 (亮色); 暗色下由全局 element-dark.scss 覆盖为半透明品牌色 */
/deep/ .el-table .failed-row td { background: #fef0f0 !important }
/deep/ .el-table .running-row td { background: #f0f9eb !important }
</style>
