<template>
  <div class="login-page">
    <!-- 主题切换浮动按钮 -->
    <el-tooltip :content="theme === 'dark' ? '切换到亮色' : '切换到暗色'" placement="left">
      <el-button
        circle
        class="theme-toggle"
        :icon="theme === 'dark' ? 'el-icon-sunny' : 'el-icon-moon'"
        @click="toggleTheme" />
    </el-tooltip>

    <!-- 背景装饰: 与登录页保持同一套视觉 -->
    <svg class="bg-grid" viewBox="0 0 1440 900" preserveAspectRatio="xMidYMid slice" aria-hidden="true">
      <defs>
        <linearGradient id="line-grad" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0%"  stop-color="var(--bg-grid-color)" stop-opacity="0" />
          <stop offset="50%" stop-color="var(--bg-grid-color)" stop-opacity=".7" />
          <stop offset="100%" stop-color="var(--bg-grid-color)" stop-opacity="0" />
        </linearGradient>
        <pattern id="dots" width="28" height="28" patternUnits="userSpaceOnUse">
          <circle cx="2" cy="2" r="1" fill="var(--bg-dot-color)" />
        </pattern>
      </defs>
      <path d="M0,420 C320,360 640,560 1440,400" stroke="url(#line-grad)" stroke-width="1.2" fill="none" />
      <path d="M0,560 C320,640 720,520 1440,620" stroke="url(#line-grad)" stroke-width="1.2" fill="none" />
      <path d="M0,720 C360,680 720,820 1440,720" stroke="url(#line-grad)" stroke-width="1.2" fill="none" />
      <circle cx="180" cy="160" r="220" fill="var(--bg-glow-a)" />
      <circle cx="1280" cy="760" r="260" fill="var(--bg-glow-b)" />
      <rect x="900" y="60" width="220" height="220" fill="url(#dots)" opacity=".6" />
    </svg>

    <span class="orb orb-1"></span>
    <span class="orb orb-2"></span>

    <!-- 单栏卡片: 注册表单字段较多, 不再做左右分区 -->
    <div class="register-card">
      <div class="card-head">
        <div class="brand-logo">
          <i class="el-icon-data-analysis"></i>
        </div>
        <h2 class="card-title">创建账号</h2>
        <p class="card-slogan">注册后拥有独立的数据空间 · 只能看到自己的数据</p>
      </div>

      <el-form ref="form" :model="form" :rules="rules" @submit.native.prevent="onRegister">
        <el-form-item prop="username">
          <label class="field-label">账号</label>
          <el-input
            v-model="form.username"
            prefix-icon="el-icon-user"
            placeholder="4-20 位, 字母开头, 可含数字下划线" />
        </el-form-item>

        <el-form-item prop="nickName">
          <label class="field-label">昵称</label>
          <el-input
            v-model="form.nickName"
            prefix-icon="el-icon-s-custom"
            placeholder="选填, 不填则与账号相同" />
        </el-form-item>

        <el-form-item prop="password">
          <label class="field-label">密码</label>
          <el-input
            v-model="form.password"
            prefix-icon="el-icon-lock"
            type="password"
            placeholder="5-20 位"
            show-password />
        </el-form-item>

        <el-form-item prop="confirmPassword">
          <label class="field-label">确认密码</label>
          <el-input
            v-model="form.confirmPassword"
            prefix-icon="el-icon-lock"
            type="password"
            placeholder="请再次输入密码"
            show-password
            @keyup.enter.native="onRegister" />
        </el-form-item>

        <el-form-item prop="email">
          <label class="field-label">邮箱 (选填)</label>
          <el-input
            v-model="form.email"
            prefix-icon="el-icon-message"
            placeholder="填了可用邮箱验证码登录" />
        </el-form-item>

        <el-form-item prop="phonenumber">
          <label class="field-label">手机号 (选填)</label>
          <el-input
            v-model="form.phonenumber"
            prefix-icon="el-icon-mobile-phone"
            placeholder="填了可用短信验证码登录" />
        </el-form-item>

        <transition name="fade">
          <div v-if="errorMsg" class="field-tip">
            <i class="el-icon-warning-outline"></i>
            <span>{{ errorMsg }}</span>
          </div>
        </transition>

        <el-button type="primary" :loading="loading" class="login-btn" @click="onRegister">
          <span v-if="!loading">注 册</span>
        </el-button>
      </el-form>

      <p class="register-entry">
        已有账号？
        <a href="javascript:;" class="forgot-link" @click="$router.push('/login')">去登录</a>
      </p>
    </div>
  </div>
</template>

<script>
import { register } from '@/api/auth'

export default {
  name: 'Register',
  data () {
    const checkConfirm = (rule, value, callback) => {
      if (value !== this.form.password) callback(new Error('两次输入的密码不一致'))
      else callback()
    }
    return {
      loading: false,
      errorMsg: '',
      theme: 'light',
      form: {
        username: '',
        nickName: '',
        password: '',
        confirmPassword: '',
        email: '',
        phonenumber: ''
      },
      rules: {
        username: [
          { required: true, message: '请输入账号', trigger: 'blur' },
          { pattern: /^[a-zA-Z][a-zA-Z0-9_]{3,19}$/, message: '4-20 位, 字母开头, 只能含字母数字下划线', trigger: 'blur' }
        ],
        password: [
          { required: true, message: '请输入密码', trigger: 'blur' },
          { min: 5, max: 20, message: '密码长度需在 5-20 位之间', trigger: 'blur' }
        ],
        confirmPassword: [
          { required: true, message: '请再次输入密码', trigger: 'blur' },
          { validator: checkConfirm, trigger: 'blur' }
        ],
        email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
        phonenumber: [{ pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }]
      }
    }
  },
  mounted () {
    if (window.__datamoveTheme) this.theme = window.__datamoveTheme.get()
  },
  methods: {
    toggleTheme () {
      if (window.__datamoveTheme) this.theme = window.__datamoveTheme.toggle()
    },
    onRegister () {
      this.$refs.form.validate(ok => {
        if (!ok) return
        this.loading = true
        this.errorMsg = ''
        // 注册接口自己接管错误提示(账号已存在等), 避免重复弹窗
        register(this.form.username, this.form.password, this.form.nickName,
          this.form.email, this.form.phonenumber)
          .then(() => {
            // 后端注册时已返回 token, 这里仍走一次登录 action:
            // 复用成熟流程把 token / 用户信息 / 菜单一次性装进 store
            return this.$store.dispatch('login', {
              username: this.form.username,
              password: this.form.password
            })
          })
          .then(() => {
            this.$message.success('注册成功, 欢迎使用 DataMove')
            this.$router.push('/')
          })
          .catch(err => {
            this.errorMsg = err.message || '注册失败, 请稍后重试'
          })
          .finally(() => {
            this.loading = false
          })
      })
    }
  }
}
</script>

<style scoped>
/* 页面骨架与登录页一致: 柔和背景 + 居中卡片 + 主题变量驱动 */
.login-page {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  overflow: hidden;
  padding: 24px;
  background:
    radial-gradient(1200px 800px at 0% 0%, #f4f7fc 0%, transparent 55%),
    radial-gradient(1000px 700px at 100% 100%, #f1f5fa 0%, transparent 55%),
    var(--bg-page);

  --bg-grid-color: #b8c4d4;
  --bg-dot-color: #cbd5e1;
  --bg-glow-a: rgba(96, 165, 250, .10);
  --bg-glow-b: rgba(167, 139, 250, .08);
}
html.theme-dark .login-page {
  background:
    radial-gradient(1200px 800px at 0% 0%, #1a2230 0%, transparent 55%),
    radial-gradient(1000px 700px at 100% 100%, #16181f 0%, transparent 55%),
    var(--bg-page);

  --bg-grid-color: #2c3645;
  --bg-dot-color: #2a3340;
  --bg-glow-a: rgba(64, 158, 255, .14);
  --bg-glow-b: rgba(139, 92, 246, .10);
}

.bg-grid {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  z-index: 0;
  pointer-events: none;
  opacity: .55;
}
html.theme-dark .bg-grid { opacity: .7 }

.orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(90px);
  pointer-events: none;
  z-index: 0;
  animation: orb-float 20s ease-in-out infinite;
}
.orb-1 { width: 380px; height: 380px; top: 8%; left: 8%; background: rgba(96, 165, 250, .35) }
.orb-2 {
  width: 460px; height: 460px; bottom: -10%; right: 6%;
  background: rgba(167, 139, 250, .28);
  animation-delay: -10s;
}
html.theme-dark .orb-1 { background: rgba(64, 158, 255, .4) }
html.theme-dark .orb-2 { background: rgba(139, 92, 246, .32) }
@keyframes orb-float {
  0%, 100% { transform: translate(0, 0) scale(1) }
  50%      { transform: translate(40px, 30px) scale(1.06) }
}

/* ---------- 卡片 ---------- */
.register-card {
  position: relative;
  z-index: 1;
  width: 440px;
  max-width: 100%;
  max-height: calc(100vh - 48px);
  overflow-y: auto;
  padding: 32px 36px 24px;
  background: var(--bg-card);
  border-radius: 20px;
  border: 1px solid var(--color-border);
  box-shadow:
    0 30px 80px rgba(15, 23, 42, .12),
    0 8px 20px rgba(15, 23, 42, .06);
  animation: card-in .5s cubic-bezier(.2, .7, .2, 1) both;
}
html.theme-dark .register-card {
  box-shadow:
    0 30px 80px rgba(0, 0, 0, .55),
    0 8px 20px rgba(0, 0, 0, .35),
    inset 0 1px 0 rgba(255, 255, 255, .04);
}
@keyframes card-in {
  from { opacity: 0; transform: translateY(20px) scale(.98) }
  to   { opacity: 1; transform: translateY(0) scale(1) }
}

.card-head { text-align: center; margin-bottom: 22px }
.brand-logo {
  width: 44px;
  height: 44px;
  margin: 0 auto 12px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  color: #fff;
  background: linear-gradient(135deg, #409eff 0%, #6f42c1 100%);
  box-shadow: 0 6px 16px rgba(64, 158, 255, .30);
}
.card-title {
  margin: 0 0 6px;
  font-size: 20px;
  font-weight: 600;
  color: var(--color-text-primary);
}
.card-slogan {
  margin: 0;
  font-size: 12px;
  color: var(--color-text-secondary);
}

/* ---------- 字段 ---------- */
.field-label {
  display: block;
  font-size: 11px;
  font-weight: 600;
  color: var(--color-text-regular);
  margin-bottom: 5px;
  letter-spacing: .5px;
  text-transform: uppercase;
}
.login-page /deep/ .el-form-item { margin-bottom: 16px }
.login-page /deep/ .el-input__inner {
  height: 40px;
  line-height: 40px;
  border-radius: 8px;
  font-size: 14px;
  transition: all .2s ease;
}
.login-page /deep/ .el-input__inner:hover { border-color: var(--color-primary) }
.login-page /deep/ .el-input__inner:focus { box-shadow: 0 0 0 3px rgba(64, 158, 255, .16) }
.login-page /deep/ .el-input__prefix { font-size: 15px; color: var(--color-text-secondary) }
.login-page /deep/ .el-input.is-focus .el-input__prefix { color: var(--color-primary) }

/* ---------- 错误提示 ---------- */
.field-tip {
  margin: 0 0 14px;
  padding: 6px 10px;
  background: rgba(245, 108, 108, .10);
  border: 1px solid rgba(245, 108, 108, .25);
  color: #f56c6c;
  font-size: 12px;
  border-radius: 6px;
  display: flex;
  align-items: center;
  line-height: 1.4;
  animation: shake .35s ease;
}
html.theme-dark .field-tip { background: rgba(245, 108, 108, .15); color: #ff8585 }
.field-tip i { margin-right: 6px; font-size: 13px }
@keyframes shake {
  0%, 100% { transform: translateX(0) }
  25%      { transform: translateX(-4px) }
  75%      { transform: translateX(4px) }
}

/* ---------- 按钮 ---------- */
.login-btn {
  width: 100%;
  height: 42px;
  letter-spacing: 4px;
  font-weight: 600;
  font-size: 14px;
  border-radius: 8px;
  background: linear-gradient(135deg, #409eff 0%, #6f42c1 100%) !important;
  border: none !important;
  box-shadow: 0 6px 16px rgba(64, 158, 255, .30), inset 0 1px 0 rgba(255, 255, 255, .2);
  transition: all .25s ease;
}
.login-btn:hover { transform: translateY(-1px) }

.register-entry {
  margin: 16px 0 0;
  text-align: center;
  font-size: 12px;
  color: var(--color-text-secondary);
}
.forgot-link {
  color: var(--color-primary);
  text-decoration: none;
  transition: opacity .2s;
}
.forgot-link:hover { opacity: .75; text-decoration: underline }

.theme-toggle {
  position: absolute;
  top: 22px;
  right: 26px;
  z-index: 2;
  background: var(--bg-card);
  border: 1px solid var(--color-border);
}

.fade-enter-active, .fade-leave-active { transition: opacity .2s ease }
.fade-enter, .fade-leave-to { opacity: 0 }

@media (max-width: 768px) {
  .register-card { padding: 24px 20px 18px; border-radius: 16px }
}
</style>
