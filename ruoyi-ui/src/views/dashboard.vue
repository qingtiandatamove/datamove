<template>
  <div>
    <el-row :gutter="16">
      <el-col :span="6"><el-card><div class="stat"><span class="lbl">运行中任务</span><span class="val">{{ stats.running }}</span></div></el-card></el-col>
      <el-col :span="6"><el-card><div class="stat"><span class="lbl">已完成</span><span class="val">{{ stats.completed }}</span></div></el-card></el-col>
      <el-col :span="6"><el-card><div class="stat"><span class="lbl">数据源</span><span class="val">{{ stats.datasource }}</span></div></el-card></el-col>
      <el-col :span="6"><el-card><div class="stat"><span class="lbl">累计同步行数</span><span class="val">{{ stats.totalRows }}</span></div></el-card></el-col>
    </el-row>

    <el-card style="margin-top:16px">
      <div slot="header"><b>使用帮助</b></div>
      <ol>
        <li>进入 <a href="javascript:;" @click="$router.push('/sync/datasource')">数据源管理</a> 添加源库和目标库</li>
        <li>进入 <a href="javascript:;" @click="$router.push('/sync/task')">同步任务</a> 创建一个全量/增量任务</li>
        <li>点击「启动」即可开始同步,支持断点续传与钉钉告警</li>
        <li>遇到问题可在 <a href="javascript:;" @click="$router.push('/sync/log')">同步日志</a> 中排查</li>
      </ol>
    </el-card>
  </div>
</template>

<script>
import { listDataSource } from '@/api/datamove'
import { pageTask } from '@/api/datamove'

export default {
  data () { return { stats: { running: 0, completed: 0, datasource: 0, totalRows: 0 } } },
  mounted () { this.load() },
  methods: {
    async load () {
      const a = await listDataSource().catch(() => ({ data: [] }))
      this.stats.datasource = (a.data || []).length
      const b = await pageTask({ pageNum: 1, pageSize: 1000 }).catch(() => ({ data: { rows: [], total: 0 } }))
      const rows = b.data?.rows || []
      this.stats.running   = rows.filter(r => r.status === 'RUNNING').length
      this.stats.completed = rows.filter(r => r.status === 'COMPLETED').length
    }
  }
}
</script>

<style scoped>
.stat { display:flex; flex-direction:column; }
.stat .lbl { color:#888; font-size:13px }
.stat .val { font-size:32px; font-weight:bold; color:#1890ff; margin-top:6px }
</style>
