<template>
  <div>
    <el-card>
      <div slot="header" class="clearfix">
        <span>同步任务</span>
        <el-button-group style="float:right">
          <el-button type="primary" icon="el-icon-plus" size="mini" @click="onAdd('FULL')">新建全量任务</el-button>
          <el-button type="success" icon="el-icon-plus" size="mini" @click="onAdd('INCR')">新建增量任务</el-button>
          <el-button type="warning" icon="el-icon-plus" size="mini" @click="onAdd('DDL')">同步表结构</el-button>
        </el-button-group>
      </div>

      <el-form :inline="true" :model="query">
        <el-form-item><el-input v-model="query.keyword" placeholder="任务名称/表名" size="small" clearable /></el-form-item>
        <el-form-item>
          <el-select v-model="query.taskType" size="small" clearable placeholder="任务类型" style="width:140px">
            <el-option label="全量" value="FULL" />
            <el-option label="增量" value="INCR" />
            <el-option label="同步表结构" value="DDL" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-select v-model="query.status" size="small" clearable placeholder="状态" style="width:140px">
            <el-option label="未启动" value="STOP" />
            <el-option label="运行中" value="RUNNING" />
            <el-option label="已暂停" value="PAUSE" />
            <el-option label="已完成" value="COMPLETED" />
            <el-option label="失败" value="FAILED" />
          </el-select>
        </el-form-item>
        <el-form-item><el-button type="primary" size="small" icon="el-icon-search" @click="load">查询</el-button></el-form-item>
      </el-form>

      <el-table :data="page.rows" v-loading="loading" border class="sync-task-table"
        @sort-change="onSortChange">
        <el-table-column prop="id" label="ID" width="70" sortable="custom" :sort-orders="['ascending','descending']" />
        <el-table-column prop="taskName" label="任务名称" min-width="160" show-overflow-tooltip />
        <el-table-column prop="taskType" label="类型" width="90">
          <template slot-scope="s">
            <el-tag size="mini" :type="taskTypeTag(s.row.taskType)">
              {{ taskTypeName(s.row.taskType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="syncMode" label="模式" width="85">
          <template slot-scope="s">{{ modeName(s.row.syncMode) }}</template>
        </el-table-column>
        <el-table-column prop="overwriteFlag" label="覆盖" width="70" align="center">
          <template slot-scope="s">
            <el-tag v-if="s.row.taskType === 'FULL' && s.row.overwriteFlag === 1" size="mini" type="danger">覆盖</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="tableName" label="表名" min-width="180" show-overflow-tooltip />
        <el-table-column prop="batchSize" label="批次" width="75">
          <template slot-scope="s">{{ s.row.taskType === 'DDL' ? '-' : s.row.batchSize }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="110">
          <template slot-scope="s">
            <el-tag size="mini" :type="statusType(s.row.status)">{{ statusName(s.row.status) }}</el-tag>
          </template>
        </el-table-column>
        <!-- 创建时间: 可按时间排序, 默认按 id 排序时此列无图标 -->
        <el-table-column prop="createTime" label="创建时间" width="170" sortable="custom" :sort-orders="['descending','ascending']">
          <template slot-scope="s">{{ fmtTime(s.row.createTime) }}</template>
        </el-table-column>
        <!-- 操作列: 按钮平铺, 通过去 icon + CSS 收紧 padding 减少宽度; 列宽按 FULL/INCR 7 按钮自然宽 -->
        <el-table-column label="操作" min-width="420">
          <template slot-scope="s">
            <!-- DDL 类型: 单次操作, 不支持暂停/继续/停止 -->
            <template v-if="s.row.taskType === 'DDL'">
              <el-button size="mini" type="success"
                :disabled="s.row.status === 'RUNNING'"
                @click="onStart(s.row)" icon="el-icon-document-add">
                {{ s.row.status === 'COMPLETED' ? '再次同步' : (s.row.status === 'FAILED' ? '重试' : '同步表结构') }}
              </el-button>
              <el-button size="mini" @click="onLog(s.row)">日志</el-button>
              <el-button size="mini" type="primary" @click="onEdit(s.row)">编辑</el-button>
              <el-button size="mini" type="danger" @click="onDel(s.row)">删除</el-button>
            </template>
            <template v-else>
              <!-- 启动: 运行中禁用; 完成/未启动/暂停/失败 都可点 (文案随状态变化) -->
              <el-button size="mini" type="success"
                :disabled="s.row.status === 'RUNNING'"
                @click="onStart(s.row)">
                {{ s.row.status === 'COMPLETED' ? '重新启动' : (s.row.status === 'FAILED' ? '重试' : '启动') }}
              </el-button>
              <!-- 暂停: 仅运行中可点 -->
              <el-button size="mini" :disabled="s.row.status !== 'RUNNING'" @click="onPause(s.row)">暂停</el-button>
              <!-- 继续: 仅已暂停可点 -->
              <el-button size="mini" type="warning" :disabled="s.row.status !== 'PAUSE'" @click="onResume(s.row)">继续</el-button>
              <!-- 停止: 运行中或暂停可点 -->
              <el-button size="mini" type="danger" :disabled="!['RUNNING','PAUSE'].includes(s.row.status)" @click="onStop(s.row)">停止</el-button>
              <!-- 重置: 仅 FULL 任务, 运行中不可重置 -->
              <el-button size="mini" type="info"
                :disabled="s.row.status === 'RUNNING'"
                @click="onReset(s.row)">重置</el-button>
              <el-button size="mini" @click="onLog(s.row)">日志</el-button>
              <el-button size="mini" type="primary" @click="onEdit(s.row)">编辑</el-button>
              <el-button size="mini" type="danger" @click="onDel(s.row)">删除</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        style="margin-top:16px" background layout="prev, pager, next, total"
        :total="page.total" :page-size="query.pageSize" :current-page.sync="query.pageNum" @current-change="load" />
    </el-card>

    <!-- 新增/编辑弹窗 -->
    <el-dialog :title="form.id ? '编辑任务' : '新建任务'" :visible.sync="dialog" width="900px" @closed="onDialogClosed">
      <el-tabs v-model="tabActive" :before-leave="onBeforeTabLeave">
        <el-tab-pane label="基本信息" name="base">
          <el-form ref="form" :model="form" :rules="rules" label-width="110px">
            <el-form-item label="任务名称" prop="taskName"><el-input v-model="form.taskName" /></el-form-item>
            <el-form-item label="任务类型" prop="taskType">
              <el-radio-group v-model="form.taskType" @change="onTaskTypeChange">
                <el-radio-button label="FULL">全量</el-radio-button>
                <el-radio-button label="INCR">增量</el-radio-button>
                <el-radio-button label="DDL">同步表结构</el-radio-button>
              </el-radio-group>
            </el-form-item>

            <!-- DDL 类型: 不需要选同步模式 -->
            <template v-if="form.taskType === 'FULL'">
              <el-form-item label="同步模式" prop="syncMode">
                <el-radio-group v-model="form.syncMode">
                  <el-radio-button label="ID">按主键ID</el-radio-button>
                  <el-radio-button label="TIME">按时间字段</el-radio-button>
                </el-radio-group>
              </el-form-item>
            </template>
            <template v-else-if="form.taskType === 'INCR'">
              <el-form-item label="同步模式" prop="syncMode"><el-tag>Binlog 增量</el-tag></el-form-item>
            </template>
            <template v-else>
              <el-form-item label="同步模式"><el-tag type="warning">仅同步表结构 (DDL)</el-tag></el-form-item>
            </template>

            <el-form-item label="源数据源" prop="sourceId">
              <el-select v-model="form.sourceId" filterable style="width:100%">
                <el-option v-for="d in datasources" :key="d.id" :value="d.id" :label="d.datasourceName + ' (' + d.host + ')'" />
              </el-select>
            </el-form-item>
            <el-form-item label="目标数据源" prop="targetId">
              <el-select v-model="form.targetId" filterable style="width:100%">
                <el-option v-for="d in datasources" :key="d.id" :value="d.id" :label="d.datasourceName + ' (' + d.host + ')'" />
              </el-select>
            </el-form-item>

            <el-form-item :label="form.taskType === 'DDL' ? '源表名' : '同步表名'" prop="tableName">
              <el-select v-model="form.tableName" filterable allow-create style="width:100%" placeholder="可手动输入或选择">
                <el-option v-for="t in sourceTables" :key="t" :value="t" :label="t" />
              </el-select>
            </el-form-item>

            <template v-if="form.taskType === 'FULL'">
              <el-form-item v-if="form.syncMode === 'ID'" label="ID字段名">
                <el-input v-model="form.idField" placeholder="默认 id" />
              </el-form-item>
              <template v-if="form.syncMode === 'TIME'">
                <el-form-item label="时间字段"><el-input v-model="form.timeField" placeholder="默认 update_time" /></el-form-item>
                <el-form-item label="起始时间"><el-date-picker v-model="form.startTime" type="datetime" value-format="yyyy-MM-dd HH:mm:ss" /></el-form-item>
              </template>
              <el-form-item v-if="form.syncMode === 'ID'" label="起始ID"><el-input-number v-model="form.startId" :min="0" /></el-form-item>
              <el-form-item label="批次大小" prop="batchSize"><el-input-number v-model="form.batchSize" :min="100" :max="100000" /></el-form-item>
              <el-form-item label="覆盖数据">
                <el-switch v-model="form.overwriteFlag" :active-value="1" :inactive-value="0" />
                <span style="margin-left:8px;color:#909399;font-size:12px">开启后每次启动会先清空目标表,再全量写入</span>
              </el-form-item>
            </template>

            <template v-else-if="form.taskType === 'INCR'">
              <el-form-item label="Canal Host"><el-input v-model="form.canalHost" /></el-form-item>
              <el-form-item label="Canal Port"><el-input-number v-model="form.canalPort" :min="1" :max="65535" /></el-form-item>
              <el-form-item label="Destination"><el-input v-model="form.canalDestination" /></el-form-item>
            </template>

            <template v-else>
              <el-alert type="info" :closable="false" show-icon
                title="DDL 同步说明"
                description="启动后会把源表结构复制到目标库; 目标表不存在会自动建表, 已存在则跳过 (不会覆盖现有数据)。"/>
            </template>

            <el-form-item label="钉钉告警"><el-input v-model="form.dingtalkWebhook" placeholder="https://oapi.dingtalk.com/robot/send?access_token=xxx" /></el-form-item>
            <el-form-item label="邮件告警">
              <el-input v-model="form.alertEmail" placeholder="多个邮箱用英文逗号分隔, 如 ops@a.com,dev@b.com" />
              <div style="color:#909399;font-size:12px;line-height:18px;margin-top:4px">
                留空则不发送邮件; 需服务端配置 SMTP 服务器并开启 sync.mail.enabled=true 后生效
              </div>
            </el-form-item>
            <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" /></el-form-item>
          </el-form>
        </el-tab-pane>

        <!-- 字段映射: 仅 FULL / INCR 显示; DDL 不需要 -->
        <el-tab-pane v-if="form.taskType !== 'DDL'" label="字段映射" name="mapping">
          <el-alert type="info" :closable="false" show-icon style="margin-bottom:12px"
            title="字段映射说明"
            :description="mappingHint"/>
          <div class="fm-toolbar">
            <el-button size="mini" @click="reloadMapping" icon="el-icon-refresh" :loading="fmLoading">刷新列</el-button>
            <el-button size="mini" type="warning" @click="clearMappings" icon="el-icon-delete"
                       :disabled="!mappings.length">清空映射 (回到同名模式)</el-button>
            <span class="fm-stat">
              已配对 <b class="ok">{{ mappings.length }}</b> 对 / 源 {{ sourceFields.length + mappings.length }} 字段 / 目标 {{ targetFields.length + mappings.length }} 字段
            </span>
          </div>

          <div class="fm-stage" ref="fmStage" v-loading="fmLoading">
            <!-- 左: 源字段 -->
            <div class="fm-col fm-col-src">
              <div class="fm-col-head">源字段 (sourceId = {{ form.sourceId || '-' }}, table = {{ form.tableName || '-' }})</div>
              <div class="fm-col-body">
                <div v-if="!sourceFields.length" class="fm-empty">无字段, 请先填写源数据源与表名</div>
                <div v-for="f in sourceFields" :key="'s-' + f.name"
                     class="fm-item fm-item-src"
                     :class="{ 'fm-item-dim': isSrcMapped(f.name) }"
                     :data-name="f.name"
                     @mousedown="onSrcMouseDown($event, f)">
                  <span class="fm-item-name">{{ f.name }}</span>
                  <span class="fm-item-type">{{ f.type }}</span>
                </div>
              </div>
            </div>

            <!-- 中: SVG 连线层 (覆盖在两栏之间) -->
            <svg class="fm-svg" :viewBox="fmViewBox" preserveAspectRatio="none">
              <g v-for="(m, i) in mappings" :key="'m-' + i + '-' + m.sourceField" class="fm-line">
                <path :d="m.path" class="fm-line-path" />
                <circle :cx="midX(m)" :cy="midY(m)" r="8" class="fm-line-close-bg" @click="removeMapping(i)" />
                <text :x="midX(m)" :y="midY(m) + 3" text-anchor="middle" class="fm-line-close-x"
                      @click="removeMapping(i)">×</text>
              </g>
              <!-- 拖拽中的临时线 -->
              <path v-if="drag.active" :d="drag.path" class="fm-line-drag" />
            </svg>

            <!-- 右: 目标字段 -->
            <div class="fm-col fm-col-tgt">
              <div class="fm-col-head">目标字段 (targetId = {{ form.targetId || '-' }}, table = {{ form.tableName || '-' }})</div>
              <div class="fm-col-body">
                <div v-if="!targetFields.length" class="fm-empty">无字段, 请先填写目标数据源与表名</div>
                <div v-for="f in targetFields" :key="'t-' + f.name"
                     class="fm-item fm-item-tgt"
                     :class="{ 'fm-item-dim': isTgtMapped(f.name) }"
                     :data-name="f.name"
                     @mouseup="onTgtMouseUp($event, f)">
                  <span class="fm-item-name">{{ f.name }}</span>
                  <span class="fm-item-type">{{ f.type }}</span>
                </div>
              </div>
            </div>
          </div>

          <div class="fm-hint-row">
            <i class="el-icon-info" /> 按住源字段往右拖拽到目标字段即可建立映射; 连线上的 × 可删除映射
          </div>
        </el-tab-pane>
      </el-tabs>

      <div slot="footer">
        <el-button @click="dialog=false">取消</el-button>
        <el-button type="primary" @click="onSave" :loading="saving">保 存</el-button>
      </div>
    </el-dialog>

    <!-- 日志弹窗 -->
    <el-dialog :title="'任务日志 [ID=' + taskLogId + ']'" :visible.sync="logDialog" width="900px" @open="loadLogs">
      <el-table :data="logPage.rows" v-loading="logLoading" border max-height="500">
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="batchNo" label="批次" width="60" />
        <el-table-column prop="batchStartId" label="起始" width="130" />
        <el-table-column prop="batchEndId" label="结束" width="130" />
        <el-table-column prop="batchRows" label="行数" width="70" />
        <el-table-column prop="totalRows" label="累计" width="100" />
        <el-table-column prop="costMs" label="耗时(ms)" width="90" />
        <el-table-column prop="status" label="状态" width="80">
          <template slot-scope="s">
            <el-tag size="mini" :type="s.row.status === 'SUCCESS' ? 'success' : 'danger'">{{ s.row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="errorMsg" label="错误" show-overflow-tooltip />
        <el-table-column prop="createTime" label="时间" width="160" />
      </el-table>
      <el-pagination style="margin-top:10px" background layout="prev, pager, next, total"
        :total="logPage.total" :page-size="logQuery.pageSize"
        :current-page.sync="logQuery.pageNum" @current-change="loadLogs" />
    </el-dialog>
  </div>
</template>

<script>
import { pageTask, addTask, updateTask, deleteTask,
         startTask, pauseTask, resumeTask, stopTask, resetTask, pageLog,
         listDataSource, listTables, listColumns,
         listFieldMapping, saveFieldMapping, clearFieldMapping } from '@/api/datamove'

export default {
  data () {
    return {
      query: { keyword: '', taskType: '', status: '', pageNum: 1, pageSize: 10, orderByColumn: 'id', isAsc: 'asc' },
      page: { rows: [], total: 0 },
      loading: false, dialog: false, saving: false,
      form: { taskType: 'FULL', syncMode: 'ID', batchSize: 1000 },
      rules: {
        taskName: [{ required: true, message: '必填' }],
        taskType: [{ required: true }],
        syncMode: [{ required: true }],
        sourceId: [{ required: true }],
        targetId: [{ required: true }],
        tableName: [{ required: true }],
        batchSize: [{ required: true }]
      },
      datasources: [], sourceTables: [],
      logDialog: false, logLoading: false, taskLogId: 0,
      logQuery: { pageNum: 1, pageSize: 10 }, logPage: { rows: [], total: 0 },
      pollTimer: null, refreshing: false,

      /* ============ 字段映射 ============ */
      tabActive: 'base',
      fmLoading: false,
      // 已配对的映射 (按 sort_no 升序); 每条: { sourceField, targetField, path }
      mappings: [],
      // 当前未配对的源/目标字段 (从 sources 待合)
      sourceFields: [],   // [{ name, type }]
      targetFields: [],   // [{ name, type }]
      // 拖拽临时状态
      drag: { active: false, startX: 0, startY: 0, curX: 0, curY: 0, srcName: '', path: '' },
      // 已保存的映射原始列表 (用于保存前检测变化)
      originalMappings: []
    }
  },
  computed: {
    /** kettle 连线 SVG viewBox: 0..100 宽度, 高度自适应 */
    fmViewBox () {
      return '0 0 100 ' + Math.max(120, this.mappings.length * 24 + 40)
    },
    mappingHint () {
      return '按 kettle 风格拖拽源字段到目标字段, 建立一对一字段重命名映射。' +
             '不建立映射 = 按源/目标字段同名同步(原行为, 老任务不受影响)。' +
             '建立后: SELECT 按源字段读、INSERT 按目标字段写, 自动建表也会用目标列名。'
    }
  },
  watch: {
    'form.sourceId' () {
      this.fetchTables()
      this.fetchColumns()
    },
    'form.targetId' () {
      this.fetchColumns()
    },
    'form.tableName' () {
      this.fetchColumns()
    }
  },
  mounted () {
    this.load()
    this.loadDatasources()
    this.startPolling()
    window.addEventListener('mousemove', this.onDocMouseMove)
    window.addEventListener('mouseup', this.onDocMouseUp)
  },
  beforeDestroy () {
    this.stopPolling()
    window.removeEventListener('mousemove', this.onDocMouseMove)
    window.removeEventListener('mouseup', this.onDocMouseUp)
  },
  methods: {
    load () {
      this.loading = true
      pageTask(this.query).then(r => { this.page = r.data }).catch(() => {}).finally(() => this.loading = false)
    },
    loadDatasources () {
      listDataSource().then(r => { this.datasources = r.data || [] }).catch(() => {})
    },
    fetchTables () {
      if (!this.form.sourceId) return
      listTables(this.form.sourceId).then(r => { this.sourceTables = r.data || [] }).catch(() => {})
    },
    /**
     * 拉源/目标两边的列; 拉完后 reconcileMappings 把已配对映射与列集合对齐
     */
    async fetchColumns () {
      if (!this.form.sourceId || !this.form.tableName) { this.sourceFields = []; return }
      if (!this.form.targetId) { this.targetFields = []; return }
      this.fmLoading = true
      try {
        const [src, tgt] = await Promise.all([
          listColumns(this.form.sourceId, this.form.tableName),
          listColumns(this.form.targetId, this.form.tableName)
        ])
        this.sourceFields = (src.data || []).map(c => ({ name: c.columnName, type: c.dataType || c.columnType || '' }))
        this.targetFields = (tgt.data || []).map(c => ({ name: c.columnName, type: c.dataType || c.columnType || '' }))
        this.rebuildMappingPaths()
      } catch (e) {
        // 已配对的 path 也需要重算 (表格列数变化后 src/tgt 坐标会变)
        this.rebuildMappingPaths()
      } finally {
        this.fmLoading = false
      }
    },
    reloadMapping () { this.fetchColumns() },
    clearMappings () {
      if (!this.mappings.length) return
      this.$confirm('确认清空当前任务的所有字段映射? 清空后回到"按字段名同名"同步 (老行为)。', '提示', { type: 'warning' })
        .then(() => {
          // 拆掉所有配对 -> 把字段送回 sourceFields/targetFields
          const pairs = this.mappings.slice()
          this.mappings = []
          pairs.forEach(m => {
            if (!this.sourceFields.some(f => f.name === m.sourceField)) this.sourceFields.push({ name: m.sourceField, type: '' })
            if (!this.targetFields.some(f => f.name === m.targetField)) this.targetFields.push({ name: m.targetField, type: '' })
          })
        }).catch(() => {})
    },

    onAdd (type) {
      // DDL 类型不需要 batchSize / idField / timeField, syncMode 填 'DDL' 占位即可
      const base = { taskType: type, syncMode: type === 'FULL' ? 'ID' : (type === 'DDL' ? 'DDL' : 'BINLOG'), idField: 'id', timeField: 'update_time', overwriteFlag: 0 }
      if (type !== 'DDL') base.batchSize = 1000
      this.dialog = true; this.form = base; this.tabActive = 'base'
      this.mappings = []; this.sourceFields = []; this.targetFields = []; this.originalMappings = []
    },
    onEdit (row) {
      this.dialog = true; this.form = Object.assign({}, row); this.tabActive = 'base'
      this.mappings = []; this.sourceFields = []; this.targetFields = []; this.originalMappings = []
      // 拉一次字段 (异步; tab 切到 mapping 时也再拉一次)
      this.fetchColumns()
      // 已配对映射: 已存在任务直接拉
      if (row.id) {
        listFieldMapping(row.id).then(r => {
          const list = r.data || []
          // 把已配对映射写入 mappings; 不进 sourceFields/targetFields (从可选列表移除)
          this.mappings = list.map((m, i) => ({
            sourceField: m.sourceField, targetField: m.targetField, sortNo: m.sortNo == null ? i : m.sortNo,
            path: ''
          }))
          this.originalMappings = list.map(m => ({ sourceField: m.sourceField, targetField: m.targetField }))
        }).catch(() => {})
      }
    },
    onDialogClosed () {
      this.form = {}
      this.mappings = []; this.sourceFields = []; this.targetFields = []; this.originalMappings = []
      this.tabActive = 'base'
    },
    /** tab 切换前: 仅校验当前 tab 内已填字段; mapping tab 自动 fetchColumns */
    onBeforeTabLeave (to, from) {
      if (to === 'mapping' && (!this.sourceFields.length || !this.targetFields.length)) {
        this.fetchColumns()
      }
      return true
    },
    onTaskTypeChange (val) {
      if (val === 'DDL') {
        this.tabActive = 'base'
      }
      // 切换任务类型后清空 mapping (老 mapping 不再适用)
      this.mappings = []; this.sourceFields = []; this.targetFields = []
    },
    async onSave () {
      this.$refs.form.validate(ok => {
        if (!ok) { this.tabActive = 'base'; return }
        // DDL 类型不需要 batchSize, 若没填则用 100 占位 (后端不依赖该值)
        if (this.form.taskType === 'DDL' && !this.form.batchSize) this.form.batchSize = 100
        this.saving = true
        const api = this.form.id ? updateTask : addTask
        api(this.form).then(r => {
          const newId = this.form.id || (r && r.data)
          // 保存字段映射 (新增任务也会保存, 用后端返回的 id)
          return Promise.resolve(newId).then(id => {
            if (!id) return
            const payload = this.mappings.map((m, i) => ({
              taskId: id, sourceField: m.sourceField, targetField: m.targetField, sortNo: i
            }))
            return saveFieldMapping(id, payload).catch(err => {
              // 映射保存失败: 任务已经保存, 提示但不让用户以为没保存
              this.$message.warning('任务已保存, 但字段映射保存失败: ' + (err.message || ''))
            })
          })
        }).then(() => { this.$message.success('已保存'); this.dialog = false; this.load() })
          .catch(err => { this.$message.error('保存失败:' + (err.message || '未知错误')) })
          .finally(() => { this.saving = false })
      })
    },
    onDel (row) {
      this.$confirm(`确认删除任务 [${row.taskName}]?`, '提示', { type: 'warning' })
        .then(() => deleteTask(row.id))
        .then(() => { this.$message.success('已删除'); this.load() }).catch(() => {})
    },
    onStart (row)  { startTask(row.id).then(() => { this.$message.success('已启动'); this.refreshTasks() }).catch(err => { this.$message.error('启动失败:' + (err.message || '')) }) },
    onPause (row)  { pauseTask(row.id).then(() => { this.$message.success('已暂停'); this.refreshTasks() }).catch(err => { this.$message.error('暂停失败:' + (err.message || '')) }) },
    onResume (row) { resumeTask(row.id).then(() => { this.$message.success('已继续'); this.refreshTasks() }).catch(err => { this.$message.error('继续失败:' + (err.message || '')) }) },
    onStop (row)   { stopTask(row.id).then(() => { this.$message.success('已停止'); this.refreshTasks() }).catch(err => { this.$message.error('停止失败:' + (err.message || '')) }) },
    onReset (row) {
      this.$confirm(`确认重置任务 [${row.taskName}] 的同步进度?\n清空断点后下次启动会从头全量同步,历史日志保留`, '重置确认', { type: 'warning' })
        .then(() => resetTask(row.id))
        .then(() => { this.$message.success('已重置, 下次启动将从头同步'); this.refreshTasks() })
        .catch(err => {
          if (err && err !== 'cancel' && process.env.NODE_ENV !== 'production') {
            // eslint-disable-next-line no-console
            console.error('[reset]', err.message || err)
          }
        })
    },

    /**
     * 自动轮询: 只在有任务处于 RUNNING 时定时拉取最新状态
     */
    startPolling () {
      this.stopPolling()
      this.pollTimer = setInterval(() => {
        const rows = (this.page && this.page.rows) || []
        const hasRunning = rows.some(r => r.status === 'RUNNING')
        if (hasRunning) this.refreshTasks()
      }, 3000)
    },
    stopPolling () {
      if (this.pollTimer) {
        clearInterval(this.pollTimer)
        this.pollTimer = null
      }
    },
    refreshTasks () {
      if (this.refreshing) return
      this.refreshing = true
      pageTask(this.query).then(r => {
        const oldStatuses = (this.page.rows || []).reduce((m, r) => (m[r.id] = r.status, m), {})
        this.page = r.data || { rows: [], total: 0 }
        ;(this.page.rows || []).forEach(r => {
          if (oldStatuses[r.id] && oldStatuses[r.id] !== r.status) {
            const map = { COMPLETED: '已完成', FAILED: '失败', RUNNING: '运行中', PAUSE: '已暂停', STOP: '已停止' }
            this.$notify({
              title: '任务状态变化',
              message: `[${r.taskName}] ${map[oldStatuses[r.id]] || oldStatuses[r.id]} → ${map[r.status] || r.status}`,
              type: r.status === 'FAILED' ? 'error' : (r.status === 'COMPLETED' ? 'success' : 'info'),
              duration: 2500
            })
          }
        })
        const stillRunning = (this.page.rows || []).some(r => r.status === 'RUNNING')
        if (!stillRunning) {
          this.stopPolling()
          this.startPolling()
        }
      }).catch(() => {}).finally(() => { this.refreshing = false })
    },

    onLog (row) { this.taskLogId = row.id; this.logDialog = true },
    loadLogs () {
      this.logLoading = true
      pageLog({ ...this.logQuery, taskId: this.taskLogId }).then(r => { this.logPage = r.data }).catch(() => {}).finally(() => this.logLoading = false)
    },

    onSortChange ({ prop, order }) {
      if (!order) {
        this.query.orderByColumn = 'id'
        this.query.isAsc = 'asc'
      } else {
        this.query.orderByColumn = prop
        this.query.isAsc = order === 'ascending' ? 'asc' : 'desc'
      }
      this.query.pageNum = 1
      this.load()
    },
    fmtTime (t) {
      if (!t) return '-'
      const d = new Date(t)
      if (isNaN(d.getTime())) return '-'
      const p = n => String(n).padStart(2, '0')
      return `${d.getFullYear()}-${p(d.getMonth()+1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
    },

    resetForm () { this.form = {} },
    modeName (m) { return ({ ID: '按ID', TIME: '按时间', BINLOG: 'Binlog', DDL: '表结构' })[m] || m },
    statusName (s) { return ({ STOP: '未启动', RUNNING: '运行中', PAUSE: '已暂停', COMPLETED: '已完成', FAILED: '失败' })[s] || s },
    statusType (s) { return ({ STOP: 'info', RUNNING: 'success', PAUSE: 'warning', COMPLETED: '', FAILED: 'danger' })[s] || '' },
    taskTypeName (t) { return ({ FULL: '全量', INCR: '增量', DDL: '表结构' })[t] || t },
    taskTypeTag (t) { return ({ FULL: '', INCR: 'success', DDL: 'warning' })[t] || '' },

    /* ============ 字段映射 - kettle 风格拖拽连线 ============ */

    isSrcMapped (n) { return this.mappings.some(m => m.sourceField === n) },
    isTgtMapped (n) { return this.mappings.some(m => m.targetField === n) },

    /**
     * 取某源字段在 stage 中的"右边缘中点"坐标 (SVG viewBox 坐标: 0..100)
     */
    srcAnchor (name) {
      const stage = this.$refs.fmStage
      if (!stage) return { x: 50, y: 20 }
      const el = stage.querySelector('.fm-item-src[data-name="' + cssEscape(name) + '"]')
      if (!el) return null
      const sRect = stage.getBoundingClientRect()
      const eRect = el.getBoundingClientRect()
      return {
        x: (eRect.right - sRect.left) / sRect.width * 100,
        y: (eRect.top + eRect.height / 2 - sRect.top) / sRect.height * 100
      }
    },
    tgtAnchor (name) {
      const stage = this.$refs.fmStage
      if (!stage) return { x: 50, y: 20 }
      const el = stage.querySelector('.fm-item-tgt[data-name="' + cssEscape(name) + '"]')
      if (!el) return null
      const sRect = stage.getBoundingClientRect()
      const eRect = el.getBoundingClientRect()
      return {
        x: (eRect.left - sRect.left) / sRect.width * 100,
        y: (eRect.top + eRect.height / 2 - sRect.top) / sRect.height * 100
      }
    },

    /** 计算每条已配对 mapping 的 svg 路径 (贝塞尔曲线) */
    rebuildMappingPaths () {
      // 在 svg viewBox 里; x 是 src 右锚, x2 是 target 左锚
      this.mappings.forEach(m => {
        const s = this.srcAnchor(m.sourceField)
        const t = this.tgtAnchor(m.targetField)
        if (!s || !t) { m.path = ''; return }
        const dx = (t.x - s.x) * 0.5
        m.path = `M ${s.x} ${s.y} C ${s.x + dx} ${s.y}, ${t.x - dx} ${t.y}, ${t.x} ${t.y}`
      })
    },
    midX (m) {
      const s = this.srcAnchor(m.sourceField); const t = this.tgtAnchor(m.targetField)
      return (s && t) ? (s.x + t.x) / 2 : 50
    },
    midY (m) {
      const s = this.srcAnchor(m.sourceField); const t = this.tgtAnchor(m.targetField)
      return (s && t) ? (s.y + t.y) / 2 : 20
    },

    onSrcMouseDown (ev, field) {
      // 已配对的字段不允许再拖
      if (this.isSrcMapped(field.name)) return
      ev.preventDefault()
      const s = this.srcAnchor(field.name)
      if (!s) return
      // 转换为 stage 相对坐标
      const stage = this.$refs.fmStage
      const sRect = stage.getBoundingClientRect()
      const x = (ev.clientX - sRect.left) / sRect.width * 100
      const y = (ev.clientY - sRect.top) / sRect.height * 100
      this.drag = { active: true, srcName: field.name, startX: s.x, startY: s.y, curX: x, curY: y,
                    path: `M ${s.x} ${s.y} C ${s.x + (x - s.x) / 2} ${s.y}, ${x - (x - s.x) / 2} ${y}, ${x} ${y}` }
    },
    onDocMouseMove (ev) {
      if (!this.drag.active) return
      const stage = this.$refs.fmStage
      if (!stage) return
      const sRect = stage.getBoundingClientRect()
      const x = (ev.clientX - sRect.left) / sRect.width * 100
      const y = (ev.clientY - sRect.top) / sRect.height * 100
      this.drag.curX = x; this.drag.curY = y
      const s = { x: this.drag.startX, y: this.drag.startY }
      const dx = (x - s.x) * 0.5
      this.drag.path = `M ${s.x} ${s.y} C ${s.x + dx} ${s.y}, ${x - dx} ${y}, ${x} ${y}`
    },
    onDocMouseUp () {
      if (!this.drag.active) return
      // mouseup 在 target div 上由 onTgtMouseUp 处理, 这里只是收尾
      this.drag.active = false
      this.drag.path = ''
      this.drag.srcName = ''
    },
    onTgtMouseUp (ev, field) {
      // 只有拖拽过程中且未配对的释放点才接受
      if (!this.drag.active) return
      if (this.isTgtMapped(field.name)) return
      // 形成配对
      const srcName = this.drag.srcName
      if (!srcName || srcName === field.name) return
      this.mappings.push({ sourceField: srcName, targetField: field.name, sortNo: this.mappings.length, path: '' })
      // 从 sourceFields / targetFields 移除 (避免重复配对)
      this.sourceFields = this.sourceFields.filter(f => f.name !== srcName)
      this.targetFields = this.targetFields.filter(f => f.name !== field.name)
      this.drag.active = false; this.drag.path = ''; this.drag.srcName = ''
      this.$nextTick(() => this.rebuildMappingPaths())
    },
    removeMapping (i) {
      const m = this.mappings[i]
      if (!m) return
      this.mappings.splice(i, 1)
      // 把字段送回可选列表
      if (!this.sourceFields.some(f => f.name === m.sourceField)) this.sourceFields.push({ name: m.sourceField, type: '' })
      if (!this.targetFields.some(f => f.name === m.targetField)) this.targetFields.push({ name: m.targetField, type: '' })
      this.$nextTick(() => this.rebuildMappingPaths())
    }
  }
}

/**
 * CSS attribute selector 转义: data-name="user_name" 不需要转义,
 * 但若字段名含空格/斜杠等特殊字符要转义。本项目字段名通常安全, 提供一个最小版本.
 */
function cssEscape (s) {
  if (s == null) return ''
  return String(s).replace(/(["\\])/g, '\\$1')
}
</script>

<style scoped>
/* 同步任务列表 - 操作列按钮紧凑 */
.sync-task-table >>> .el-table .cell .el-button--mini {
  padding: 5px 8px;
  font-size: 12px;
}
.sync-task-table >>> .el-table td:last-child .cell {
  display: flex;
  flex-wrap: nowrap;
  align-items: center;
  justify-content: space-between;
  white-space: nowrap;
  padding-left: 8px;
  padding-right: 8px;
}

/* ============ 字段映射 - kettle 风格 ============ */
.fm-toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 8px }
.fm-stat    { color: #909399; font-size: 12px; margin-left: auto }
.fm-stat b  { color: #409EFF; font-weight: 600 }
.fm-stat b.ok { color: #67C23A }

.fm-stage {
  position: relative;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 80px;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  background: #fafafa;
  min-height: 320px;
  padding: 8px;
}
.fm-svg {
  position: absolute;
  left: 0; top: 0; width: 100%; height: 100%;
  pointer-events: none;
  z-index: 2;
}
.fm-svg .fm-line, .fm-svg .fm-line-drag { pointer-events: auto }
.fm-line-path { stroke: #409EFF; stroke-width: 1.5; fill: none; opacity: 0.85 }
.fm-line-drag { stroke: #67C23A; stroke-width: 1.5; fill: none; stroke-dasharray: 4 3 }
.fm-line-close-bg { fill: #fff; stroke: #F56C6C; stroke-width: 1; cursor: pointer }
.fm-line-close-x  { fill: #F56C6C; font-size: 12px; cursor: pointer; font-family: Arial }

.fm-col { background: #fff; border: 1px solid #ebeef5; border-radius: 4px; overflow: hidden; z-index: 1 }
.fm-col-head {
  background: #f5f7fa;
  padding: 6px 10px;
  font-size: 12px;
  color: #606266;
  border-bottom: 1px solid #ebeef5;
}
.fm-col-body { max-height: 360px; overflow-y: auto; padding: 4px 0 }
.fm-empty { color: #909399; font-size: 12px; padding: 16px; text-align: center }

.fm-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 10px;
  margin: 2px 4px;
  border: 1px solid transparent;
  border-radius: 3px;
  cursor: grab;
  user-select: none;
  font-size: 13px;
  background: #fff;
}
.fm-item:hover { background: #ecf5ff; border-color: #b3d8ff; }
.fm-item-src:active { cursor: grabbing }
.fm-item-tgt { cursor: default }
.fm-item-dim { opacity: 0.35; cursor: not-allowed; background: #f5f7fa }
.fm-item-name { font-weight: 500; color: #303133; font-family: Menlo, Consolas, monospace }
.fm-item-type { color: #909399; font-size: 11px; margin-left: 8px }

.fm-hint-row {
  margin-top: 8px; font-size: 12px; color: #909399;
  display: flex; align-items: center; gap: 4px;
}
.fm-hint-row i { color: #409EFF }
</style>