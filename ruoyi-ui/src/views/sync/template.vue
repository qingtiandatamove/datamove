<template>
  <el-card>
    <div slot="header">
      <span>模板市场</span>
      <span class="header-tip">选一个场景模板, 填源库/目标库/表名即可创建任务; 创建后仍是普通任务, 参数可随意改</span>
    </div>

    <el-row :gutter="14" v-loading="loading">
      <el-col :span="8" v-for="t in templates" :key="t.code" class="tpl-col">
        <div class="tpl-card" :class="{ 'is-active': current && current.code === t.code }">
          <div class="tpl-head">
            <i :class="t.icon" class="tpl-icon" />
            <div class="tpl-title">
              <div class="tpl-name">{{ t.name }}</div>
              <div class="tpl-desc">{{ t.desc }}</div>
            </div>
          </div>

          <div class="tpl-scene">适用: {{ t.scenario }}</div>

          <div class="tpl-tags">
            <el-tag v-for="tag in t.tags" :key="tag" size="mini" type="info">{{ tag }}</el-tag>
          </div>

          <div class="tpl-params">
            <span v-if="t.config.taskType === 'INCR'">类型 增量 / Canal {{ t.config.canalHost }}:{{ t.config.canalPort }}</span>
            <span v-else>类型 {{ typeLabel(t.config.taskType) }} / {{ modeLabel(t.config.syncMode) }}游标</span>
            <span>批次 {{ t.config.batchSize }}</span>
            <span>分片 {{ t.config.shardCount }}</span>
            <span>{{ t.config.overwriteFlag === 1 ? '覆盖写入' : '追加写入' }}</span>
            <span v-if="t.config.triggerType === 'CRON'">定时 {{ t.config.cronExpr }}</span>
            <span v-if="t.config.ignoreFields">忽略 {{ t.config.ignoreFields.split(',').length }} 个字段</span>
          </div>

          <div class="tpl-actions">
            <el-button size="mini" @click="onDetail(t)">详情</el-button>
            <el-button v-if="$hasPerm('sync:template:apply')" size="mini" type="primary"
              icon="el-icon-magic-stick" @click="onApply(t)">一键套用</el-button>
          </div>
        </div>
      </el-col>
    </el-row>

    <!-- 模板详情: 预置参数 + 提示 + 限制, 套用前让人看清楚 -->
    <el-dialog :title="current ? current.name : '模板详情'" :visible.sync="detailDialog" width="620px">
      <template v-if="current">
        <div class="detail-desc">{{ current.desc }}</div>
        <el-descriptions :column="2" border size="small" class="detail-params">
          <el-descriptions-item label="任务类型">{{ typeLabel(current.config.taskType) }}</el-descriptions-item>
          <el-descriptions-item label="同步游标">{{ modeLabel(current.config.syncMode) || '-' }}</el-descriptions-item>
          <el-descriptions-item label="批次大小">{{ current.config.batchSize }}</el-descriptions-item>
          <el-descriptions-item label="分片数">{{ current.config.shardCount }}</el-descriptions-item>
          <el-descriptions-item label="写入方式">{{ current.config.overwriteFlag === 1 ? '覆盖写入' : '追加写入' }}</el-descriptions-item>
          <el-descriptions-item label="调度方式">{{ triggerLabel(current.config.triggerType) }}</el-descriptions-item>
          <el-descriptions-item label="CRON 表达式" :span="2">{{ current.config.cronExpr || '-' }}</el-descriptions-item>
          <el-descriptions-item label="忽略字段" :span="2">{{ current.config.ignoreFields || '-' }}</el-descriptions-item>
        </el-descriptions>

        <div class="detail-block" v-if="current.tips && current.tips.length">
          <div class="detail-block-title">使用提示</div>
          <ul class="detail-list">
            <li v-for="(tip, i) in current.tips" :key="i">{{ tip }}</li>
          </ul>
        </div>
        <div class="detail-block" v-if="current.limitation">
          <div class="detail-block-title warn">限制</div>
          <div class="detail-limit">{{ current.limitation }}</div>
        </div>
      </template>
      <div slot="footer">
        <el-button @click="detailDialog=false">关闭</el-button>
        <el-button v-if="$hasPerm('sync:template:apply')" type="primary" @click="onApply(current)">一键套用</el-button>
      </div>
    </el-dialog>

    <!-- 套用向导: 只问跟当前环境有关的东西 -->
    <el-dialog :title="current ? '套用模板 - ' + current.name : '套用模板'" :visible.sync="applyDialog" width="600px">
      <el-form :model="form" label-width="110px" v-if="current">
        <el-form-item label="源数据源" required>
          <el-select v-model="form.sourceId" filterable style="width:100%" placeholder="选择源库">
            <el-option v-for="d in datasources" :key="d.id" :label="d.datasourceName" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标数据源" required>
          <el-select v-model="form.targetId" filterable style="width:100%" placeholder="选择目标库">
            <el-option v-for="d in datasources" :key="d.id" :label="d.datasourceName" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="同步表" required>
          <el-select v-if="tables.length" v-model="form.tableName" filterable clearable style="width:100%"
            placeholder="从源库选择, 也可手动输入">
            <el-option v-for="t in tables" :key="t" :label="t" :value="t" />
          </el-select>
          <el-input v-else v-model="form.tableName" placeholder="表名, 如 orders" />
        </el-form-item>
        <el-form-item label="任务名称">
          <el-input v-model="form.taskName" :placeholder="defaultTaskName" />
        </el-form-item>

        <!-- 模板要求什么就显示什么: 缺了这些任务跑不起来 -->
        <el-form-item v-if="current.config.needIdField" label="主键字段" required>
          <el-input v-model="form.idField" placeholder="如 id, 数值型自增列最佳" />
          <div class="field-tip">ID 游标按主键分片, 字符串主键也能跑但分片效率低</div>
        </el-form-item>
        <el-form-item v-if="current.config.needTimeField" label="时间字段" required>
          <el-input v-model="form.timeField" placeholder="如 update_time" />
          <div class="field-tip">该字段必须有索引, 否则每批都是全表扫描</div>
        </el-form-item>
        <el-form-item v-if="current.config.triggerType === 'CRON'" label="CRON 表达式" required>
          <el-input v-model="form.cronExpr" placeholder="0 0 2 * * ?" />
          <div class="field-tip">Spring 6 位格式: 秒 分 时 日 月 周</div>
        </el-form-item>
        <template v-if="current.config.taskType === 'INCR'">
          <el-form-item label="Canal 地址" required>
            <el-input v-model="form.canalHost" placeholder="127.0.0.1" />
          </el-form-item>
          <el-form-item label="Canal 端口" required>
            <el-input-number v-model="form.canalPort" :min="1" :max="65535" />
          </el-form-item>
          <el-form-item label="Destination" required>
            <el-input v-model="form.canalDestination" placeholder="example" />
          </el-form-item>
        </template>
        <el-form-item v-if="current.config.ignoreFields" label="忽略字段">
          <el-input v-model="form.ignoreFields" type="textarea" :rows="2" placeholder="逗号分隔" />
        </el-form-item>
        <el-form-item label="告警邮箱">
          <el-input v-model="form.alertEmail" placeholder="选填, 失败时发邮件(需先配置 SMTP)" />
        </el-form-item>
        <el-form-item label="字段映射">
          <el-checkbox v-model="form.autoMapping">按同名列自动生成</el-checkbox>
          <div class="field-tip">需要目标表已存在; 生成失败不影响任务创建, 可到任务里手动配</div>
        </el-form-item>
      </el-form>
      <div slot="footer">
        <el-button @click="applyDialog=false">取消</el-button>
        <el-button type="primary" :loading="applying" @click="onSubmit">创建任务</el-button>
      </div>
    </el-dialog>
  </el-card>
</template>

<script>
import { listTemplates, applyTemplate, listDataSource, listTables } from '@/api/datamove'

export default {
  data () {
    return {
      templates: [], loading: false, current: null,
      detailDialog: false, applyDialog: false, applying: false,
      datasources: [], tables: [],
      form: {}
    }
  },
  computed: {
    defaultTaskName () {
      if (!this.current) return ''
      return this.current.name + '-' + (this.form.tableName || '表名')
    }
  },
  mounted () { this.load(); this.loadDatasources() },
  methods: {
    load () {
      this.loading = true
      listTemplates().then(r => { this.templates = r.data || [] }).catch(() => {})
        .finally(() => { this.loading = false })
    },
    loadDatasources () {
      listDataSource().then(r => { this.datasources = r.data || [] }).catch(() => {})
    },
    loadTables (dsId) {
      this.tables = []
      if (!dsId) return
      listTables(dsId).then(r => { this.tables = r.data || [] }).catch(() => {})
    },

    onDetail (t) {
      this.current = t
      this.detailDialog = true
    },
    onApply (t) {
      this.current = t
      const c = t.config || {}
      this.form = {
        sourceId: null,
        targetId: null,
        tableName: '',
        taskName: '',
        idField: c.needIdField ? 'id' : '',
        timeField: c.needTimeField ? 'update_time' : '',
        cronExpr: c.cronExpr || '',
        canalHost: c.canalHost || '',
        canalPort: c.canalPort || 11111,
        canalDestination: c.canalDestination || '',
        ignoreFields: c.ignoreFields || '',
        alertEmail: '',
        autoMapping: true
      }
      this.tables = []
      this.detailDialog = false
      this.applyDialog = true
    },
    onSubmit () {
      const f = this.form
      if (!f.sourceId || !f.targetId) { this.$message.warning('请选择源数据源与目标数据源'); return }
      if (!f.tableName) { this.$message.warning('请填写或选择同步表'); return }
      const c = this.current.config || {}
      if (c.needIdField && !f.idField) { this.$message.warning('请填写主键字段名'); return }
      if (c.needTimeField && !f.timeField) { this.$message.warning('请填写时间字段名'); return }
      if (c.triggerType === 'CRON' && !f.cronExpr) { this.$message.warning('请填写 CRON 表达式'); return }
      if (c.taskType === 'INCR' && (!f.canalHost || !f.canalDestination)) {
        this.$message.warning('请填写 Canal 地址与 Destination')
        return
      }
      if (f.sourceId === f.targetId) { this.$message.warning('源库与目标库不能相同'); return }

      this.applying = true
      applyTemplate(this.current.code, f).then(r => {
        const d = r.data || {}
        const note = d.mappingCount ? `, 自动生成 ${d.mappingCount} 条字段映射` : ''
        this.$message.success(`已创建任务「${d.taskName}」${note}`)
        this.applyDialog = false
        this.$confirm('任务已创建, 是否前往任务列表查看?', '套用成功', { type: 'success' })
          .then(() => { this.$router.push('/sync/task') }).catch(() => {})
      }).catch(() => {}).finally(() => { this.applying = false })
    },

    typeLabel (t) { return { FULL: '全量', INCR: '增量', DDL: '表结构' }[t] || t },
    modeLabel (m) { return { ID: 'ID', TIME: '时间', DDL: '' }[m] || '' },
    triggerLabel (t) { return { MANUAL: '手动', CRON: '定时', EVENT: '事件触发' }[t] || t }
  },
  watch: {
    'form.sourceId' (v) { this.loadTables(v) }
  }
}
</script>

<style scoped>
.header-tip { color: #909399; font-size: 12px; margin-left: 10px }
.tpl-col { margin-bottom: 14px }
.tpl-card {
  border: 1px solid var(--color-border, #ebeef5); border-radius: 6px; padding: 14px; height: 100%;
  display: flex; flex-direction: column; transition: box-shadow .2s, border-color .2s;
}
.tpl-card:hover { box-shadow: 0 2px 12px rgba(0, 0, 0, .1); border-color: #409EFF }
.tpl-card.is-active { border-color: #409EFF }
.tpl-head { display: flex; align-items: flex-start }
.tpl-icon { font-size: 26px; color: #409EFF; margin-right: 10px; margin-top: 2px }
.tpl-title { flex: 1; min-width: 0 }
.tpl-name { font-size: 15px; font-weight: 600; color: var(--color-text-primary) }
.tpl-desc { color: #909399; font-size: 12px; margin-top: 4px; line-height: 17px }
.tpl-scene { color: #606266; font-size: 12px; margin-top: 10px }
.tpl-tags { margin-top: 8px }
.tpl-tags .el-tag { margin-right: 4px; margin-bottom: 4px }
.tpl-params {
  margin-top: 8px; font-size: 12px; color: #909399; line-height: 20px;
  display: flex; flex-direction: column;
}
.tpl-actions { margin-top: auto; padding-top: 12px; display: flex; gap: 8px }

.detail-desc { color: #606266; font-size: 13px; margin-bottom: 12px }
.detail-params { margin-bottom: 12px }
.detail-block { margin-top: 12px }
.detail-block-title { font-size: 13px; font-weight: 600; margin-bottom: 6px; color: var(--color-text-primary) }
.detail-block-title.warn { color: #E6A23C }
.detail-list { margin: 0; padding-left: 18px; color: #606266; font-size: 12px; line-height: 20px }
.detail-limit {
  color: #E6A23C; font-size: 12px; line-height: 18px; background: rgba(230, 162, 60, .08);
  border-left: 3px solid #E6A23C; padding: 8px 10px; border-radius: 3px;
}
.field-tip { color: #909399; font-size: 12px; line-height: 17px; margin-top: 2px }
</style>
