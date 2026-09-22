<template>
  <el-card>
    <div slot="header"><span>授权管理</span></div>

    <!-- 模块关闭时的友好提示 -->
    <el-alert v-if="disabled" type="info" :closable="false" title="授权模块未启用">
      <p>当前配置 <code>sync.license.enabled=false</code> (默认), Spring 不会实例化 LicenseService 与授权接口。</p>
      <p>这是 <b>开源版</b> 行为 —— 没有 MAC 校验, 也没有过期拦截, 跑起来无任何授权障碍。</p>
      <p>启用商业授权请:</p>
      <ol>
        <li><code>application.yml</code> 里把 <code>sync.license.enabled</code> 改为 <code>true</code></li>
        <li>填入真实的 <code>sync.license-server</code> 与 <code>sync.license-key</code></li>
        <li>重启服务</li>
      </ol>
      <p>详见仓库根目录 <code>LICENSING.md</code>。</p>
    </el-alert>

    <el-form label-width="120px" style="max-width:560px" v-if="form && !disabled">
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

    <el-alert v-if="!disabled" style="margin-top:16px" type="info" :closable="false">
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
  data () { return { form: null, disabled: false } },
  mounted () { this.load() },
  methods: {
    load () {
      getLicense()
        .then(r => { this.form = r.data; this.disabled = false })
        .catch(err => {
          // 404 等未注册场景: 后端 LicenseService 因 opt-in 未启用而不存在
          if (err && (err.status === 404 || (err.message && err.message.indexOf('404') >= 0))) {
            this.disabled = true
          } else if (err && err.status === 401) {
            // 未登录: 让全局拦截器处理
            return Promise.reject(err)
          } else {
            this.$message.error('加载授权失败: ' + (err.message || ''))
          }
        })
    },
    onSave () {
      updateLicense(this.form).then(() => { this.$message.success('已保存,重启服务后生效') }).catch(err => { this.$message.error('保存失败:' + (err.message || '')) })
    }
  }
}
</script>