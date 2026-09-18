<template>
  <el-card>
    <div slot="header"><span>授权管理</span></div>

    <el-form label-width="120px" style="max-width:560px" v-if="form">
      <el-form-item label="License Key">
        <el-input v-model="form.licenseKey" disabled />
      </el-form-item>
      <el-form-item label="本机MAC">
        <el-input v-model="form.macAddress" disabled />
      </el-form-item>
      <el-form-item label="到期时间">
        <el-date-picker v-model="form.expireTime" type="datetime" value-format="yyyy-MM-dd HH:mm:ss" />
      </el-form-item>
      <el-form-item label="最大并行任务数">
        <el-input-number v-model="form.maxParallel" :min="1" :max="100" />
      </el-form-item>
      <el-form-item label="状态">
        <el-tag size="mini" :type="form.status === '0' ? 'success' : 'danger'">
          {{ form.status === '0' ? '正常' : '停用' }}
        </el-tag>
      </el-form-item>
      <el-form-item label="最后校验时间">
        <span>{{ form.lastCheckTime }}</span>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="onSave">保存(修改过期时间)</el-button>
        <el-button @click="load">刷新</el-button>
      </el-form-item>
    </el-form>

    <el-alert style="margin-top:16px" type="info" :closable="false">
      <p><b>授权规则:</b></p>
      <ul>
        <li>License Key 与本机 MAC 绑定,一机一Key,不可跨机部署</li>
        <li>仅启动时联网校验一次,运行过程可断网</li>
        <li>到期后再次重启服务将禁止启动</li>
        <li>续费:管理员在此页面直接修改 过期时间 后重启项目即可</li>
      </ul>
    </el-alert>
  </el-card>
</template>

<script>
import { getLicense, updateLicense } from '@/api/datamove'

export default {
  data () { return { form: null } },
  mounted () { this.load() },
  methods: {
    load () { getLicense().then(r => { this.form = r.data }).catch(() => {}) },
    onSave () {
      updateLicense(this.form).then(() => { this.$message.success('已保存,重启服务后生效') }).catch(err => { this.$message.error('保存失败:' + (err.message || '')) })
    }
  }
}
</script>
