<template>
  <div class="login-container">
    <!-- 主题切换按钮: 登录页独立于 layout, 这里单独放一个浮动按钮让用户未登录时也能设 -->
    <el-tooltip :content="theme === 'dark' ? '切换到亮色' : '切换到暗色'" placement="left">
      <el-button
        circle
        class="theme-toggle"
        :icon="theme === 'dark' ? 'el-icon-sunny' : 'el-icon-moon'"
        @click="toggleTheme" />
    </el-tooltip>

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
      theme: 'light',
      form: { username: 'admin', password: 'admin123' },
      rules: {
        username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
        password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
      }
    }
  },
  mounted () {
    if (window.__datamoveTheme) this.theme = window.__datamoveTheme.get()
  },
  methods: {
    showError (msg) {
      this.errorMsg = msg
      clearTimeout(this.errorTimer)
      this.errorTimer = setTimeout(() => { this.errorMsg = '' }, 4000)
    },
    toggleTheme () {
      if (!window.__datamoveTheme) return
      this.theme = window.__datamoveTheme.toggle()
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
  position: relative;
}
.login-card {
  width: 380px; padding: 28px 30px;
}
.title { display: flex; align-items: center; justify-content: center; margin-bottom: 6px }
.title h2 { margin: 0 0 0 8px; font-size: 22px }
.subtitle { text-align: center; color: var(--color-text-secondary); margin: 0 0 22px 0; font-size: 13px }
.tip { margin-top: 18px; color: var(--color-text-secondary); font-size: 12px; text-align: center }

.field-tip {
  margin-top: 6px;
  padding: 6px 10px;
  background: var(--bg-hover);
  color: var(--color-text-secondary);
  font-size: 12px;
  border-radius: 4px;
  display: flex;
  align-items: center;
  line-height: 1.4;
}
.field-tip i { margin-right: 6px; font-size: 13px; }

/* 主题切换浮动按钮 (登录页右上角) */
.theme-toggle {
  position: absolute; top: 20px; right: 20px;
  background-color: rgba(255, 255, 255, .85);
  border-color: rgba(255, 255, 255, .6);
  color: #555;
  &:hover { background-color: #fff; color: #1890ff }
}
html.theme-dark .theme-toggle {
  background-color: rgba(0, 0, 0, .4);
  border-color: rgba(255, 255, 255, .15);
  color: var(--color-warning);
  &:hover { background-color: rgba(0, 0, 0, .6); color: var(--color-warning) }
}

.fade-enter-active, .fade-leave-active { transition: opacity .2s ease; }
.fade-enter, .fade-leave-to { opacity: 0; }
</style>