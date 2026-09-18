<template>
  <div class="login-container">
    <el-card class="login-card">
      <div class="title">
        <i class="el-icon-data-analysis" style="color:#1890ff;font-size:30px"></i>
        <h2>DataMove 数据同步工具</h2>
      </div>
      <p class="subtitle">轻量 MySQL 数据同步 / 可视化零代码</p>
      <el-form ref="form" :model="form" :rules="rules" @submit.native.prevent="onLogin">
        <el-form-item prop="username">
          <el-input v-model="form.username" prefix-icon="el-icon-user" placeholder="账号" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            prefix-icon="el-icon-lock"
            type="password"
            placeholder="密码"
            show-password
            @keyup.enter.native="onLogin" />
          <transition name="fade">
            <div v-if="errorMsg" class="field-tip">
              <i class="el-icon-info"></i>
              <span>{{ errorMsg }}</span>
            </div>
          </transition>
        </el-form-item>
        <el-button type="primary" :loading="loading" style="width:100%" @click="onLogin">登录</el-button>
      </el-form>
      <p class="tip">首次登录默认账号: admin / admin123,登录后请尽快修改密码</p>
    </el-card>
  </div>
</template>

<script>
export default {
  data () {
    return {
      loading: false,
      errorMsg: '',
      errorTimer: null,
      form: { username: 'admin', password: 'admin123' },
      rules: {
        username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
        password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
      }
    }
  },
  methods: {
    showError (msg) {
      this.errorMsg = msg
      clearTimeout(this.errorTimer)
      this.errorTimer = setTimeout(() => { this.errorMsg = '' }, 4000)
    },
    onLogin () {
      this.$refs.form.validate(ok => {
        if (!ok) return
        this.loading = true
        this.$store.dispatch('login', this.form).then(() => {
          this.$message.success('登录成功')
          this.$router.push('/')
        }).catch(err => {
          this.showError(err.message || '登录失败')
        }).finally(() => { this.loading = false })
      })
    }
  }
}
</script>

<style scoped>
.login-container {
  height: 100vh; display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #1890ff 0%, #001529 100%);
}
.login-card {
  width: 380px; padding: 28px 30px;
}
.title { display: flex; align-items: center; justify-content: center; margin-bottom: 6px }
.title h2 { margin: 0 0 0 8px; font-size: 22px }
.subtitle { text-align: center; color: #888; margin: 0 0 22px 0; font-size: 13px }
.tip { margin-top: 18px; color: #999; font-size: 12px; text-align: center }

.field-tip {
  margin-top: 6px;
  padding: 6px 10px;
  background: #f4f4f5;
  color: #909399;
  font-size: 12px;
  border-radius: 4px;
  display: flex;
  align-items: center;
  line-height: 1.4;
}
.field-tip i { margin-right: 6px; font-size: 13px; }

.fade-enter-active, .fade-leave-active { transition: opacity .2s ease; }
.fade-enter, .fade-leave-to { opacity: 0; }
</style>