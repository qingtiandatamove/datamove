<template>
  <div class="ai-page">
    <el-tabs v-model="activeTab" class="ai-tabs">

    <!-- ============ 1. 配置助手(原有) ============ -->
    <el-tab-pane label="AI 配置助手" name="config">
    <!-- 新建: 一句话描述需求 -->
    <el-card class="block">
      <div slot="header">
        <span>AI 配置助手</span>
        <span class="header-tip">用一句话描述你要的同步任务, AI 自动填好配置; 确认无误再一键创建</span>
        <el-tag v-if="engine" size="mini" :type="engine === 'AI' ? 'success' : 'info'" class="engine-tag">
          {{ engine === 'AI' ? (provider ? provider + ' · ' : 'AI 模型: ') + (model || '') : '本地规则解析' }}
        </el-tag>
      </div>

      <!-- 两种解析引擎的区别说明 -->
      <el-alert class="engine-note" :closable="false" show-icon
        :type="engine === 'AI' ? 'success' : 'info'"
        :title="engine === 'AI' ? 'AI 大模型解析' : '本地规则解析'"
        :description="engineNote" />

      <el-input v-model="text" type="textarea" :rows="3" maxlength="500" show-word-limit
        placeholder="例: 把生产库 user 表同步到测试库, 只同步 status=1 的数据, 手机号脱敏, 全量 + binlog 增量, 限速 1000 条/s" />

      <div class="examples">
        <span class="ex-label">试试这些:</span>
        <el-button v-for="(e, i) in examples" :key="i" size="mini" plain @click="text = e">{{ e }}</el-button>
      </div>

      <div class="actions">
        <el-button v-if="$hasPerm('sync:ai:parse')" type="primary" icon="el-icon-magic-stick"
          :loading="parsing" @click="onParse">AI 解析</el-button>
        <el-button v-if="result" @click="result = null">清空结果</el-button>
      </div>

      <!-- 解析结果 -->
      <div v-if="result" class="result">
        <el-alert v-if="result.fallbackNote" type="info" :title="result.fallbackNote" :closable="false" show-icon />

        <div class="summary">{{ result.summary }}</div>

        <el-descriptions title="配置预览" :column="2" border size="small" class="preview">
          <el-descriptions-item label="任务名称">
            <el-input v-model="result.draft.taskName" size="mini" />
          </el-descriptions-item>
          <el-descriptions-item label="同步表">
            <el-input v-model="result.draft.tableName" size="mini" placeholder="表名" />
          </el-descriptions-item>
          <el-descriptions-item label="源数据源">
            <el-select v-model="result.draft.sourceDatasourceId" size="mini" filterable style="width:100%">
              <el-option v-for="d in datasources" :key="d.id" :label="d.datasourceName" :value="d.id" />
            </el-select>
          </el-descriptions-item>
          <el-descriptions-item label="目标数据源">
            <el-select v-model="result.draft.targetDatasourceId" size="mini" filterable style="width:100%">
              <el-option v-for="d in datasources" :key="d.id" :label="d.datasourceName" :value="d.id" />
            </el-select>
          </el-descriptions-item>
          <el-descriptions-item label="任务类型">
            <el-select v-model="result.draft.taskType" size="mini" style="width:100%">
              <el-option label="全量" value="FULL" />
              <el-option label="增量(binlog)" value="INCR" />
            </el-select>
          </el-descriptions-item>
          <el-descriptions-item label="同步模式">
            <el-select v-model="result.draft.syncMode" size="mini" style="width:100%">
              <el-option label="ID 游标" value="ID" />
              <el-option label="时间游标" value="TIME" />
              <el-option label="BINLOG" value="BINLOG" />
            </el-select>
          </el-descriptions-item>
          <el-descriptions-item label="主键字段">
            <el-input v-model="result.draft.idField" size="mini" />
          </el-descriptions-item>
          <el-descriptions-item label="时间字段">
            <el-input v-model="result.draft.timeField" size="mini" />
          </el-descriptions-item>
          <el-descriptions-item label="批次大小">
            <el-input-number v-model="result.draft.batchSize" size="mini" :min="1" :max="50000" controls-position="right" />
          </el-descriptions-item>
          <el-descriptions-item label="分片数">
            <el-input-number v-model="result.draft.shardCount" size="mini" :min="1" :max="32" controls-position="right" />
          </el-descriptions-item>
          <el-descriptions-item label="过滤条件">
            <el-input v-model="result.draft.whereCondition" size="mini" placeholder="如 status=1, 不带 WHERE" />
          </el-descriptions-item>
          <el-descriptions-item label="限速(行/秒)">
            <el-input-number v-model="result.draft.rateLimit" size="mini" :min="0" :max="200000"
              controls-position="right" placeholder="0=不限速" />
          </el-descriptions-item>
          <el-descriptions-item label="写入方式">
            <el-select v-model="result.draft.overwriteFlag" size="mini" style="width:100%">
              <el-option label="追加写入" :value="0" />
              <el-option label="覆盖写入(先清空目标表)" :value="1" />
            </el-select>
          </el-descriptions-item>
          <el-descriptions-item label="调度方式">
            <el-select v-model="result.draft.triggerType" size="mini" style="width:100%">
              <el-option label="手动" value="MANUAL" />
              <el-option label="定时(CRON)" value="CRON" />
            </el-select>
          </el-descriptions-item>
          <el-descriptions-item v-if="result.draft.triggerType === 'CRON'" label="CRON 表达式">
            <el-input v-model="result.draft.cronExpr" size="mini" placeholder="0 0 2 * * ?" />
          </el-descriptions-item>
          <el-descriptions-item label="忽略字段">
            <el-input v-model="result.draft.ignoreFields" size="mini" placeholder="逗号分隔, 这些列不写入目标库" />
          </el-descriptions-item>
          <template v-if="result.draft.taskType === 'INCR'">
            <el-descriptions-item label="Canal 地址">
              <el-input v-model="result.draft.canalHost" size="mini" />
            </el-descriptions-item>
            <el-descriptions-item label="Canal 端口">
              <el-input-number v-model="result.draft.canalPort" size="mini" :min="1" :max="65535" controls-position="right" />
            </el-descriptions-item>
            <el-descriptions-item label="Destination">
              <el-input v-model="result.draft.canalDestination" size="mini" />
            </el-descriptions-item>
            <el-descriptions-item label="binlog DML">
              <el-input v-model="result.draft.binlogDmlTypes" size="mini" placeholder="INSERT,UPDATE,DELETE" />
            </el-descriptions-item>
          </template>
          <el-descriptions-item label="字段映射" :span="2">
            <el-checkbox v-model="result.draft.autoMapping">按同名列自动生成</el-checkbox>
          </el-descriptions-item>
        </el-descriptions>

        <div v-if="result.missing && result.missing.length" class="missing">
          还缺少必填项: {{ result.missing.join('、') }} —— 在上面补齐后再创建
        </div>

        <div v-if="result.explanations && result.explanations.length" class="block-list">
          <div class="block-title">为什么这么配</div>
          <ul>
            <li v-for="(e, i) in result.explanations" :key="i">{{ e }}</li>
          </ul>
        </div>

        <div v-if="result.risks && result.risks.length" class="block-list risks">
          <div class="block-title">风险与注意事项</div>
          <div v-for="(r, i) in result.risks" :key="i" class="risk-item" :class="r.level === 'WARN' ? 'warn' : 'info'">
            <div class="risk-title">{{ r.title }}</div>
            <div class="risk-detail">{{ r.detail }}</div>
          </div>
        </div>

        <div class="actions">
          <el-button v-if="$hasPerm('sync:ai:apply')" type="success" icon="el-icon-check"
            :loading="applying" @click="onApply">确认创建任务</el-button>
        </div>
      </div>
    </el-card>

    <!-- 修改: 一句话改已有任务 -->
    <el-card class="block">
      <div slot="header">
        <span>AI 修改任务</span>
        <span class="header-tip">选一个任务, 用一句话说出要改什么, 先预览差异再确认</span>
      </div>

      <el-select v-model="modifyTaskId" filterable placeholder="选择要修改的任务" style="width: 320px">
        <el-option v-for="t in tasks" :key="t.id" :label="`#${t.id} ${t.taskName}`" :value="t.id" />
      </el-select>

      <el-input v-model="modifyText" class="modify-input" type="textarea" :rows="2" maxlength="300"
        placeholder="例: 改成只同步近 3 个月数据 / 限速调到 500 条每秒 / 改成每天凌晨 2 点跑" />

      <div class="actions">
        <el-button v-if="$hasPerm('sync:ai:parse')" type="primary" icon="el-icon-edit-outline"
          :loading="modifyPreviewing" @click="onModifyPreview">预览改动</el-button>
        <el-button v-if="modifyResult" type="success" icon="el-icon-check"
          :loading="modifyApplying" @click="onModifyApply">确认应用</el-button>
      </div>

      <div v-if="modifyResult" class="result">
        <div class="summary">{{ modifyResult.summary }}</div>
        <el-table v-if="modifyResult.changes && modifyResult.changes.length" :data="modifyResult.changes"
          size="small" border>
          <el-table-column prop="label" label="配置项" width="160" />
          <el-table-column label="原值">
            <template slot-scope="s"><span class="old">{{ s.row.oldValue || '空' }}</span></template>
          </el-table-column>
          <el-table-column label="新值">
            <template slot-scope="s"><span class="new">{{ s.row.newValue || '空' }}</span></template>
          </el-table-column>
        </el-table>
        <div v-else class="missing">这次指令没有识别出需要修改的配置项, 换句话试试</div>

        <div v-if="modifyResult.risks && modifyResult.risks.length" class="block-list risks">
          <div class="block-title">风险与注意事项</div>
          <div v-for="(r, i) in modifyResult.risks" :key="i" class="risk-item" :class="r.level === 'WARN' ? 'warn' : 'info'">
            <div class="risk-title">{{ r.title }}</div>
            <div class="risk-detail">{{ r.detail }}</div>
          </div>
        </div>
      </div>
    </el-card>
    </el-tab-pane>

    <!-- ============ 2. 字段映射推荐 ============ -->
    <el-tab-pane label="字段映射推荐" name="mapping">
      <el-card class="block">
        <div slot="header">
          <span>AI 字段映射推荐</span>
          <span class="header-tip">源表与目标表字段名不一样时, 自动配对并标出置信度; 确认后才会写入任务</span>
        </div>

        <el-alert class="engine-note" :closable="false" show-icon type="info"
          title="只给建议, 不直接改任务" description="推荐结果里字段名都经过真实表结构校验; 点「写入任务」才会替换该任务的字段映射。" />

        <el-row :gutter="12">
          <el-col :span="6">
            <div class="field-label">源数据源</div>
            <el-select v-model="mapSrcId" filterable placeholder="选择源库" style="width:100%"
              @change="onMapSrcChange">
              <el-option v-for="d in datasources" :key="d.id" :label="d.datasourceName" :value="d.id" />
            </el-select>
          </el-col>
          <el-col :span="6">
            <div class="field-label">源表</div>
            <el-select v-model="mapSrcTable" filterable allow-create clearable placeholder="选择或输入表名" style="width:100%">
              <el-option v-for="t in mapSrcTables" :key="t" :label="t" :value="t" />
            </el-select>
          </el-col>
          <el-col :span="6">
            <div class="field-label">目标数据源</div>
            <el-select v-model="mapTgtId" filterable placeholder="选择目标库" style="width:100%"
              @change="onMapTgtChange">
              <el-option v-for="d in datasources" :key="d.id" :label="d.datasourceName" :value="d.id" />
            </el-select>
          </el-col>
          <el-col :span="6">
            <div class="field-label">目标表</div>
            <el-select v-model="mapTgtTable" filterable allow-create clearable placeholder="选择或输入表名" style="width:100%">
              <el-option v-for="t in mapTgtTables" :key="t" :label="t" :value="t" />
            </el-select>
          </el-col>
        </el-row>

        <div class="actions">
          <el-button v-if="$hasPerm('sync:ai:mapping')" type="primary" icon="el-icon-magic-stick"
            :loading="mappingLoading" @click="onSuggestMapping">推荐映射</el-button>
        </div>

        <div v-if="mapResult" class="result">
          <el-alert v-if="mapResult.fallbackNote" type="info" :title="mapResult.fallbackNote" :closable="false" show-icon />
          <div class="summary">{{ mapResult.summary }}</div>

          <el-table :data="mapResult.mappings || []" size="small" border max-height="360">
            <el-table-column prop="sourceField" label="源字段" width="200" />
            <el-table-column label="→" width="40" align="center"><span>→</span></el-table-column>
            <el-table-column prop="targetField" label="目标字段" width="200" />
            <el-table-column label="置信度" width="110">
              <template slot-scope="s">
                <el-tag size="mini"
                  :type="s.row.confidence === 'high' ? 'success' : (s.row.confidence === 'mid' ? '' : 'danger')">
                  {{ s.row.confidence === 'high' ? '高' : (s.row.confidence === 'mid' ? '中' : '低') }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="reason" label="依据" />
          </el-table>

          <div class="block-list" v-if="(mapResult.unmappedSource || []).length">
            <div class="block-title">源表未匹配字段 ({{ mapResult.unmappedSource.length }})</div>
            <div class="unmapped">{{ mapResult.unmappedSource.join('、') }}</div>
          </div>
          <div class="block-list" v-if="(mapResult.unmappedTarget || []).length">
            <div class="block-title">目标表未被匹配字段 ({{ mapResult.unmappedTarget.length }})</div>
            <div class="unmapped">{{ mapResult.unmappedTarget.join('、') }}</div>
          </div>

          <div class="actions">
            <el-select v-model="mapApplyTaskId" filterable placeholder="选择要写入的任务" style="width: 320px">
              <el-option v-for="t in tasks" :key="t.id" :label="`#${t.id} ${t.taskName}`" :value="t.id" />
            </el-select>
            <el-button type="success" icon="el-icon-check" :loading="mapApplying"
              :disabled="!mapApplyTaskId || !(mapResult.mappings || []).length" @click="onApplyMapping">
              写入任务(替换现有映射)
            </el-button>
          </div>
        </div>
      </el-card>
    </el-tab-pane>

    <!-- ============ 3. 失败任务诊断 ============ -->
    <el-tab-pane label="失败诊断" name="diagnose">
      <el-card class="block">
        <div slot="header">
          <span>AI 失败任务诊断</span>
          <span class="header-tip">挑一个跑失败的任务, AI 结合配置与失败日志给原因和修复建议</span>
        </div>

        <el-select v-model="diagTaskId" filterable placeholder="选择要诊断的任务" style="width: 320px">
          <el-option v-for="t in tasks" :key="t.id" :label="`#${t.id} ${t.taskName}`" :value="t.id" />
        </el-select>

        <div class="actions">
          <el-button v-if="$hasPerm('sync:ai:diagnose')" type="primary" icon="el-icon-view"
            :loading="diagLoading" @click="onDiagnose">开始诊断</el-button>
        </div>

        <div v-if="diagResult" class="result">
          <el-alert v-if="diagResult.fallbackNote" type="info" :title="diagResult.fallbackNote" :closable="false" show-icon />

          <div class="diag-head">
            <el-tag size="small" type="danger">{{ diagResult.category || '其它' }}</el-tag>
            <span class="summary">{{ diagResult.summary }}</span>
          </div>

          <div v-if="diagResult.cause" class="block-list">
            <div class="block-title">原因分析</div>
            <div class="diag-cause">{{ diagResult.cause }}</div>
          </div>

          <div class="block-list" v-if="(diagResult.suggestions || []).length">
            <div class="block-title">修复建议</div>
            <div v-for="(s, i) in diagResult.suggestions" :key="i" class="risk-item info">
              <div class="risk-title">{{ i + 1 }}. {{ s.title }}</div>
              <div class="risk-detail" v-if="s.detail">{{ s.detail }}</div>
              <div class="risk-action" v-if="s.action">做法: {{ s.action }}</div>
            </div>
          </div>

          <div class="block-list" v-if="(diagResult.evidence || []).length">
            <div class="block-title">诊断依据</div>
            <ul>
              <li v-for="(e, i) in diagResult.evidence" :key="i">{{ e }}</li>
            </ul>
          </div>
        </div>
      </el-card>
    </el-tab-pane>

    <!-- ============ 4. 自然语言生成 SQL ============ -->
    <el-tab-pane label="生成 SQL" name="sql">
      <el-card class="block">
        <div slot="header">
          <span>AI 生成 SQL</span>
          <span class="header-tip">用中文描述想查什么, 基于真实表结构生成只读 SQL; 生成后自己看一遍再执行</span>
        </div>

        <el-row :gutter="12">
          <el-col :span="8">
            <div class="field-label">数据源</div>
            <el-select v-model="sqlDsId" filterable placeholder="选择数据源" style="width:100%"
              @change="onSqlDsChange">
              <el-option v-for="d in datasources" :key="d.id" :label="d.datasourceName" :value="d.id" />
            </el-select>
          </el-col>
          <el-col :span="8">
            <div class="field-label">指定表(可选)</div>
            <el-select v-model="sqlTable" filterable allow-create clearable placeholder="不指定则由 AI 判断" style="width:100%">
              <el-option v-for="t in sqlTables" :key="t" :label="t" :value="t" />
            </el-select>
          </el-col>
        </el-row>

        <el-input v-model="sqlQuestion" class="modify-input" type="textarea" :rows="2" maxlength="300"
          placeholder="例: 查最近 7 天注册且已完成实名认证的用户数 / 统计每个月的订单金额" />

        <div class="actions">
          <el-button v-if="$hasPerm('sync:ai:sql')" type="primary" icon="el-icon-magic-stick"
            :loading="sqlLoading" @click="onGenerateSql">生成 SQL</el-button>
        </div>

        <div v-if="sqlResult" class="result">
          <el-alert v-if="sqlResult.fallbackNote" type="info" :title="sqlResult.fallbackNote" :closable="false" show-icon />
          <el-alert v-if="sqlResult.rejected" type="error" :title="'已拦截: ' + sqlResult.rejected" :closable="false" show-icon />

          <div v-if="sqlResult.sql" class="sql-box">{{ sqlResult.sql }}</div>
          <div v-if="sqlResult.explanation" class="summary">{{ sqlResult.explanation }}</div>

          <div class="actions" v-if="sqlResult.sql">
            <el-button size="small" icon="el-icon-document-copy" @click="onCopySql">复制 SQL</el-button>
            <span class="header-tip">复制后到「SQL 工作台」粘贴执行 —— AI 不会替你执行</span>
          </div>

          <div class="block-list" v-if="(sqlResult.warnings || []).length">
            <div class="block-title">注意</div>
            <ul>
              <li v-for="(w, i) in sqlResult.warnings" :key="i">{{ w }}</li>
            </ul>
          </div>
        </div>
      </el-card>
    </el-tab-pane>

    </el-tabs>
  </div>
</template>

<script>
import {
  aiStatus, aiParse, aiApply, aiModify, aiModifyApply,
  aiSuggestMapping, aiDiagnose, aiGenerateSql,
  listDataSource, listTables, pageTask, saveFieldMapping
} from '@/api/datamove'

export default {
  computed: {
    /** 当前引擎的能力说明: 让用户知道这次结果是怎么来的、能信到什么程度 */
    engineNote () {
      if (this.engine === 'AI') {
        return '由大模型理解整句话后产出配置, 能处理复杂条件、口语化描述和隐含意图; ' +
          '本地规则解析仍会跑一遍, 只用来补 AI 漏填的字段。'
      }
      return '未配置 AI(sync.ai.api-key), 当前用关键词 + 正则解析: 认库、表、status=1 这类条件、' +
        '脱敏、限速、定时、大表分片; 复杂嵌套条件/多表/字段转换识别不了, 请在预览里手动改。' +
        '配置 AI 后自动切换为大模型解析。'
    }
  },
  data () {
    return {
      activeTab: 'config',
      text: '',
      parsing: false, applying: false,
      result: null,
      engine: '', model: '', provider: '',
      datasources: [],
      examples: [
        '把生产库 user 表同步到测试库, 只同步 status=1 的数据, 手机号脱敏, 限速 1000 条/s',
        '把业务库 orders 表每天凌晨 2 点增量同步到数仓',
        'orders 大表全量迁移到归档库, 千万级数据, 8 分片并行'
      ],
      tasks: [],
      modifyTaskId: null,
      modifyText: '',
      modifyResult: null,
      modifyPreviewing: false,
      modifyApplying: false,

      /* ---- 字段映射推荐 ---- */
      mapSrcId: null, mapSrcTable: '', mapTgtId: null, mapTgtTable: '',
      mapSrcTables: [], mapTgtTables: [],
      mappingLoading: false, mapApplying: false,
      mapResult: null, mapApplyTaskId: null,

      /* ---- 失败任务诊断 ---- */
      diagTaskId: null, diagLoading: false, diagResult: null,

      /* ---- 自然语言生成 SQL ---- */
      sqlDsId: null, sqlTable: '', sqlTables: [], sqlQuestion: '',
      sqlLoading: false, sqlResult: null
    }
  },
  mounted () {
    this.loadStatus()
    this.loadDatasources()
    this.loadTasks()
  },
  methods: {
    loadStatus () {
      aiStatus().then(r => {
        const d = r.data || {}
        this.engine = d.engine
        this.model = d.model
        this.provider = d.provider || ''
      }).catch(() => {})
    },
    loadDatasources () {
      listDataSource().then(r => { this.datasources = r.data || [] }).catch(() => {})
    },
    loadTasks () {
      pageTask({ pageNum: 1, pageSize: 200 }).then(r => {
        this.tasks = (r.data && r.data.rows) || []
      }).catch(() => {})
    },

    onParse () {
      if (!this.text || !this.text.trim()) { this.$message.warning('请先描述你的同步需求'); return }
      this.parsing = true
      aiParse(this.text.trim()).then(r => {
        const d = r.data || {}
        if (!d.draft) d.draft = {}
        if (d.draft.autoMapping === undefined) d.draft.autoMapping = true
        this.result = d
        this.engine = d.engine || this.engine
        this.model = d.model || ''
        this.provider = d.provider || this.provider
      }).catch(() => {}).finally(() => { this.parsing = false })
    },

    onApply () {
      const d = this.result && this.result.draft
      if (!d) return
      if (!d.sourceDatasourceId || !d.targetDatasourceId) { this.$message.warning('请选择源数据源与目标数据源'); return }
      if (!d.tableName) { this.$message.warning('请填写同步表'); return }
      if (d.sourceDatasourceId === d.targetDatasourceId) { this.$message.warning('源库与目标库不能相同'); return }
      if (d.triggerType === 'CRON' && !d.cronExpr) { this.$message.warning('请填写 CRON 表达式'); return }

      this.applying = true
      aiApply(d).then(r => {
        this.$message.success('任务已创建')
        this.$confirm('是否前往任务列表查看?', '创建成功', { type: 'success' })
          .then(() => { this.$router.push('/sync/task') }).catch(() => {})
      }).catch(() => {}).finally(() => { this.applying = false })
    },

    onModifyPreview () {
      if (!this.modifyTaskId) { this.$message.warning('请先选择要修改的任务'); return }
      if (!this.modifyText || !this.modifyText.trim()) { this.$message.warning('请描述要改成什么样'); return }
      this.modifyPreviewing = true
      aiModify(this.modifyTaskId, this.modifyText.trim()).then(r => {
        this.modifyResult = r.data || {}
      }).catch(() => {}).finally(() => { this.modifyPreviewing = false })
    },

    onModifyApply () {
      const r = this.modifyResult
      if (!r || !r.draft) { this.$message.warning('请先预览改动'); return }
      this.modifyApplying = true
      aiModifyApply(this.modifyTaskId, r.draft).then(res => {
        this.modifyResult = res.data || {}
        this.$message.success('任务已更新')
      }).catch(() => {}).finally(() => { this.modifyApplying = false })
    },

    /* ==================== 字段映射推荐 ==================== */
    loadTables (dsId, target) {
      if (!dsId) { this[target] = []; return }
      listTables(dsId).then(r => { this[target] = r.data || [] }).catch(() => { this[target] = [] })
    },
    onMapSrcChange (id) { this.mapSrcTable = ''; this.loadTables(id, 'mapSrcTables') },
    onMapTgtChange (id) { this.mapTgtTable = ''; this.loadTables(id, 'mapTgtTables') },

    onSuggestMapping () {
      if (!this.mapSrcId || !this.mapTgtId) { this.$message.warning('请选择源数据源与目标数据源'); return }
      if (!this.mapSrcTable || !this.mapTgtTable) { this.$message.warning('请选择或填写源表与目标表'); return }
      this.mappingLoading = true
      aiSuggestMapping({
        sourceId: this.mapSrcId, sourceTable: this.mapSrcTable,
        targetId: this.mapTgtId, targetTable: this.mapTgtTable
      }).then(res => {
        this.mapResult = res.data || {}
        const n = (this.mapResult.mappings || []).length
        if (!n) this.$message.warning('没有匹配出可用的字段映射, 检查两表结构或换个表名')
      }).catch(() => {}).finally(() => { this.mappingLoading = false })
    },

    /** 写入任务: 走原有的替换式保存接口, 与手点保存行为一致 */
    onApplyMapping () {
      if (!this.mapApplyTaskId) { this.$message.warning('请选择要写入的任务'); return }
      const list = (this.mapResult.mappings || []).map((m, i) => ({
        sourceField: m.sourceField, targetField: m.targetField, sortNo: i + 1
      }))
      if (!list.length) { this.$message.warning('没有可写入的映射'); return }
      this.mapApplying = true
      saveFieldMapping(this.mapApplyTaskId, list).then(() => {
        this.$message.success('已写入任务 #' + this.mapApplyTaskId + ' 的字段映射(替换原有配置)')
      }).catch(() => {}).finally(() => { this.mapApplying = false })
    },

    /* ==================== 失败任务诊断 ==================== */
    onDiagnose () {
      if (!this.diagTaskId) { this.$message.warning('请选择要诊断的任务'); return }
      this.diagLoading = true
      aiDiagnose(this.diagTaskId).then(res => {
        this.diagResult = res.data || {}
      }).catch(() => {}).finally(() => { this.diagLoading = false })
    },

    /* ==================== 自然语言生成 SQL ==================== */
    onSqlDsChange (id) { this.sqlTable = ''; this.loadTables(id, 'sqlTables') },

    onGenerateSql () {
      if (!this.sqlDsId) { this.$message.warning('请选择数据源'); return }
      if (!this.sqlQuestion || !this.sqlQuestion.trim()) { this.$message.warning('请先描述你想查什么'); return }
      this.sqlLoading = true
      aiGenerateSql({ dsId: this.sqlDsId, question: this.sqlQuestion.trim(), table: this.sqlTable || '' })
        .then(res => {
          this.sqlResult = res.data || {}
          if (this.sqlResult.rejected) this.$message.warning('生成的 SQL 被安全校验拦截')
        }).catch(() => {}).finally(() => { this.sqlLoading = false })
    },

    onCopySql () {
      const sql = this.sqlResult && this.sqlResult.sql
      if (!sql) return
      const ta = document.createElement('textarea')
      ta.value = sql
      document.body.appendChild(ta)
      ta.select()
      try { document.execCommand('copy'); this.$message.success('已复制') } catch (e) { this.$message.warning('复制失败, 请手动选中') }
      document.body.removeChild(ta)
    }
  }
}
</script>

<style scoped>
.block { margin-bottom: 14px }
.header-tip { color: #909399; font-size: 12px; margin-left: 10px }
.engine-tag { float: right }
.engine-note { margin-bottom: 12px; line-height: 1.7 }
.examples { margin-top: 10px }
.ex-label { color: #909399; font-size: 12px; margin-right: 8px }
.examples .el-button { margin-right: 6px; margin-bottom: 6px }
.actions { margin-top: 12px }
.result { margin-top: 16px }
.summary {
  margin: 12px 0; padding: 10px 12px; border-left: 3px solid #409EFF; background: rgba(64, 158, 255, .08);
  color: #303133; font-size: 13px; line-height: 20px;
}
.preview { margin-bottom: 12px }
.missing { color: #E6A23C; font-size: 13px; margin-bottom: 10px }
.block-list { margin-top: 14px }
.block-title { font-size: 13px; font-weight: 600; margin-bottom: 6px; color: var(--color-text-primary) }
.block-list ul { margin: 0; padding-left: 18px; color: #606266; font-size: 12px; line-height: 21px }
.risk-item { padding: 8px 10px; border-radius: 3px; margin-bottom: 8px; border-left: 3px solid #909399 }
.risk-item.warn { background: rgba(230, 162, 60, .08); border-left-color: #E6A23C }
.risk-item.info { background: rgba(144, 147, 153, .08) }
.risk-title { font-size: 13px; font-weight: 600; color: var(--color-text-primary) }
.risk-detail { font-size: 12px; color: #606266; line-height: 18px; margin-top: 2px }
.modify-input { margin-top: 10px }
.old { color: #909399; text-decoration: line-through }
.new { color: #67C23A; font-weight: 600 }

/* ---- 新增: 映射推荐 / 诊断 / 生成 SQL ---- */
.field-label { font-size: 12px; color: #909399; margin-bottom: 4px }
.unmapped { font-size: 12px; color: #909399; line-height: 20px; word-break: break-all }
.diag-head { display: flex; align-items: center; gap: 8px; margin: 12px 0 }
.diag-head .summary { margin: 0; flex: 1 }
.diag-cause { font-size: 13px; color: #606266; line-height: 21px }
.risk-action { font-size: 12px; color: #409EFF; line-height: 18px; margin-top: 4px }
.sql-box {
  margin: 12px 0; padding: 12px 14px; border-radius: 3px; background: #f5f7fa; border: 1px solid #e4e7ed;
  font-family: Menlo, Consolas, monospace; font-size: 13px; line-height: 21px; white-space: pre-wrap; word-break: break-all;
}
</style>
