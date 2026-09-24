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

    <!-- 背景装饰: 几何线条 + 浮动光斑 (取代整面大蓝) -->
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
      <!-- 装饰线条 -->
      <path d="M0,420 C320,360 640,560 1440,400" stroke="url(#line-grad)" stroke-width="1.2" fill="none" />
      <path d="M0,560 C320,640 720,520 1440,620" stroke="url(#line-grad)" stroke-width="1.2" fill="none" />
      <path d="M0,720 C360,680 720,820 1440,720" stroke="url(#line-grad)" stroke-width="1.2" fill="none" />
      <!-- 角落光斑 -->
      <circle cx="180" cy="160" r="220" fill="var(--bg-glow-a)" />
      <circle cx="1280" cy="760" r="260" fill="var(--bg-glow-b)" />
      <!-- 点阵纹理局部 -->
      <rect x="900" y="60" width="220" height="220" fill="url(#dots)" opacity=".6" />
    </svg>

    <!-- 浮动光斑 (弥补无大色块的视觉空白) -->
    <span class="orb orb-1"></span>
    <span class="orb orb-2"></span>

    <!-- 居中卡片 -->
    <div class="login-card">
      <!-- 左侧: 品牌区 (占卡片 40%) -->
      <aside class="card-brand">
        <div class="brand-logo">
          <i class="el-icon-data-analysis"></i>
        </div>
        <h1 class="brand-title">DataMove</h1>
        <p class="brand-slogan">轻量 MySQL 数据同步 · 可视化零代码</p>

        <ul class="brand-features">
          <li v-for="f in features" :key="f.text">
            <span class="feature-icon"><i :class="f.icon"></i></span>
            <span>{{ f.text }}</span>
          </li>
        </ul>

        <div class="brand-foot">
          <span class="status-dot"></span>
          <span>系统运行正常</span>
          <span class="sep">·</span>
          <span>v1.0</span>
        </div>
      </aside>

      <!-- 右侧: 表单区 (占卡片 60%) -->
      <main class="card-form">
        <div class="form-head">
          <h2>欢迎登录</h2>
          <p>Sign in to your workspace</p>
        </div>

        <el-form ref="form" :model="form" :rules="rules" @submit.native.prevent="onLogin">
          <el-form-item prop="username">
            <label class="field-label">账号</label>
            <el-input
              v-model="form.username"
              prefix-icon="el-icon-user"
              placeholder="请输入账号" />
          </el-form-item>
          <el-form-item prop="password">
            <label class="field-label">密码</label>
            <el-input
              v-model="form.password"
              prefix-icon="el-icon-lock"
              type="password"
              placeholder="请输入密码"
              show-password
              @keyup.enter.native="onLogin" />
            <transition name="fade">
              <div v-if="errorMsg" class="field-tip">
                <i class="el-icon-warning-outline"></i>
                <span>{{ errorMsg }}</span>
              </div>
            </transition>
          </el-form-item>

          <div class="form-extra">
            <el-checkbox v-model="remember">记住账号</el-checkbox>
            <a href="javascript:;" class="forgot-link">忘记密码?</a>
          </div>

          <el-button type="primary" :loading="loading" class="login-btn" @click="onLogin">
            <span v-if="!loading">登 录</span>
          </el-button>
        </el-form>

        <div class="divider"><span>其他登录方式</span></div>

        <div class="social-login">
          <a href="javascript:;" class="social-btn" title="钉钉">
            <i class="el-icon-chat-dot-square"></i>
          </a>
          <a href="javascript:;" class="social-btn" title="企业微信">
            <i class="el-icon-message"></i>
          </a>
          <a href="javascript:;" class="social-btn" title="飞书">
            <i class="el-icon-share"></i>
          </a>
          <a href="javascript:;" class="social-btn" title="SSO">
            <i class="el-icon-key"></i>
          </a>
        </div>

        <p class="copyright">
          © 2026 DataMove ·
          <a href="javascript:;" class="footer-link">服务条款</a>
          ·
          <a href="javascript:;" class="footer-link">隐私政策</a>
        </p>
      </main>
    </div>
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
      remember: true,
      form: { username: 'admin', password: 'admin123' },
      features: [
        { icon: 'el-icon-data-line',    text: '实时同步大盘' },
        { icon: 'el-icon-connection',   text: '零代码字段映射' },
        { icon: 'el-icon-refresh',      text: '断点续传不丢进度' },
        { icon: 'el-icon-bell',         text: '钉钉 / 邮件告警' },
        { icon: 'el-icon-edit-outline', text: 'SQL 工作台 · 增删改查零代码' }
      ],
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
/* ============================================================
 * 登录页 v3 - 居中卡片式
 *   抛弃整面大蓝/大白, 改成:
 *     - 整页柔和背景 (几何线条 + 角落光斑 + 浮动光球)
 *     - 中间一张卡片 (圆角 20, 白/暗色, 阴影柔和)
 *     - 卡片内左右分区 (无外部色块)
 *     - 主题变量驱动, 亮/暗自动适配
 * ============================================================ */

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
}
html.theme-dark .login-page {
  background:
    radial-gradient(1200px 800px at 0% 0%, #1a2230 0%, transparent 55%),
    radial-gradient(1000px 700px at 100% 100%, #16181f 0%, transparent 55%),
    var(--bg-page);
}

/* ---------- SVG 背景 (线条 + 角落光斑 + 点阵) ---------- */
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

/* 暴露给 SVG 内部用的 CSS 变量 (浏览器支持) */
.login-page {
  --bg-grid-color: #b8c4d4;
  --bg-dot-color: #cbd5e1;
  --bg-glow-a: rgba(96, 165, 250, .10);
  --bg-glow-b: rgba(167, 139, 250, .08);
}
html.theme-dark .login-page {
  --bg-grid-color: #2c3645;
  --bg-dot-color: #2a3340;
  --bg-glow-a: rgba(64, 158, 255, .14);
  --bg-glow-b: rgba(139, 92, 246, .10);
}

/* ---------- 浮动光斑 ---------- */
.orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(90px);
  pointer-events: none;
  z-index: 0;
  animation: orb-float 20s ease-in-out infinite;
}
.orb-1 {
  width: 380px; height: 380px;
  top: 8%; left: 8%;
  background: rgba(96, 165, 250, .35);
}
.orb-2 {
  width: 460px; height: 460px;
  bottom: -10%; right: 6%;
  background: rgba(167, 139, 250, .28);
  animation-delay: -10s;
}
html.theme-dark .orb-1 { background: rgba(64, 158, 255, .4) }
html.theme-dark .orb-2 { background: rgba(139, 92, 246, .32) }
@keyframes orb-float {
  0%, 100% { transform: translate(0, 0) scale(1) }
  50%      { transform: translate(40px, 30px) scale(1.06) }
}

/* ---------- 居中卡片 ---------- */
.login-card {
  position: relative;
  z-index: 1;
  display: flex;
  width: 880px;
  max-width: 100%;
  min-height: 540px;
  background: var(--bg-card);
  border-radius: 20px;
  border: 1px solid var(--color-border);
  box-shadow:
    0 30px 80px rgba(15, 23, 42, .12),
    0 8px 20px rgba(15, 23, 42, .06);
  overflow: hidden;
  animation: card-in .5s cubic-bezier(.2, .7, .2, 1) both;
}
html.theme-dark .login-card {
  box-shadow:
    0 30px 80px rgba(0, 0, 0, .55),
    0 8px 20px rgba(0, 0, 0, .35),
    inset 0 1px 0 rgba(255, 255, 255, .04);
}
@keyframes card-in {
  from { opacity: 0; transform: translateY(20px) scale(.98) }
  to   { opacity: 1; transform: translateY(0) scale(1) }
}

/* ---------- 卡片左侧: 品牌区 ---------- */
.card-brand {
  flex: 4;
  padding: 40px 36px;
  display: flex;
  flex-direction: column;
  background:
    radial-gradient(600px 400px at 0% 0%, rgba(96, 165, 250, .08), transparent 60%),
    radial-gradient(500px 360px at 100% 100%, rgba(167, 139, 250, .08), transparent 60%);
  border-right: 1px solid var(--color-border-light);
  position: relative;
}
html.theme-dark .card-brand {
  background:
    radial-gradient(600px 400px at 0% 0%, rgba(64, 158, 255, .10), transparent 60%),
    radial-gradient(500px 360px at 100% 100%, rgba(139, 92, 246, .08), transparent 60%);
  border-right-color: var(--color-border);
}
.brand-logo {
  width: 52px; height: 52px;
  border-radius: 14px;
  display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #409eff, #6f42c1);
  color: #fff;
  font-size: 26px;
  box-shadow: 0 8px 18px rgba(64, 158, 255, .35), inset 0 1px 0 rgba(255, 255, 255, .25);
  margin-bottom: 22px;
}
.brand-title {
  margin: 0 0 8px;
  font-size: 30px;
  font-weight: 700;
  letter-spacing: -.5px;
  color: var(--color-text-primary);
}
.brand-slogan {
  margin: 0 0 22px;
  font-size: 13px;
  color: var(--color-text-secondary);
  line-height: 1.5;
}
.brand-features {
  list-style: none;
  margin: 0;
  padding: 0;
  flex: 1;
}
.brand-features li {
  display: flex;
  align-items: center;
  margin-bottom: 14px;
  font-size: 13px;
  color: var(--color-text-regular);
}
.feature-icon {
  width: 30px; height: 30px;
  margin-right: 12px;
  border-radius: 8px;
  background: rgba(96, 165, 250, .12);
  color: var(--color-primary);
  display: flex; align-items: center; justify-content: center;
  font-size: 14px;
  flex: none;
  transition: transform .25s, background .25s;
}
.brand-features li:hover .feature-icon {
  transform: rotate(-8deg) scale(1.08);
  background: rgba(96, 165, 250, .22);
}
.brand-foot {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11px;
  color: var(--color-text-secondary);
  margin-top: 16px;
}
.status-dot {
  width: 7px; height: 7px;
  border-radius: 50%;
  background: #22c55e;
  box-shadow: 0 0 0 3px rgba(34, 197, 94, .2);
  animation: pulse-dot 2s ease-in-out infinite;
}
@keyframes pulse-dot {
  0%, 100% { box-shadow: 0 0 0 3px rgba(34, 197, 94, .18) }
  50%      { box-shadow: 0 0 0 6px rgba(34, 197, 94, .05) }
}
.sep { opacity: .4 }

/* ---------- 卡片右侧: 表单区 ---------- */
.card-form {
  flex: 6;
  padding: 40px 44px;
  display: flex;
  flex-direction: column;
  justify-content: center;
}
.form-head { margin-bottom: 22px }
.form-head h2 {
  margin: 0 0 4px;
  font-size: 22px;
  font-weight: 700;
  color: var(--color-text-primary);
  letter-spacing: -.3px;
}
.form-head p {
  margin: 0;
  font-size: 12px;
  color: var(--color-text-secondary);
  font-family: "JetBrains Mono", Consolas, monospace;
}

/* ---------- 字段 label + 输入框强化 ---------- */
.field-label {
  display: block;
  font-size: 11px;
  font-weight: 600;
  color: var(--color-text-regular);
  margin-bottom: 5px;
  letter-spacing: .5px;
  text-transform: uppercase;
}
.login-page /deep/ .el-input__inner {
  height: 40px;
  line-height: 40px;
  border-radius: 8px;
  font-size: 14px;
  transition: all .2s ease;
}
.login-page /deep/ .el-input__inner:hover { border-color: var(--color-primary) }
.login-page /deep/ .el-input__inner:focus {
  box-shadow: 0 0 0 3px rgba(64, 158, 255, .16);
}
.login-page /deep/ .el-input__prefix {
  font-size: 15px;
  color: var(--color-text-secondary);
}
.login-page /deep/ .el-input.is-focus .el-input__prefix { color: var(--color-primary) }

/* ---------- 字段错误提示 ---------- */
.field-tip {
  margin-top: 6px;
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
html.theme-dark .field-tip {
  background: rgba(245, 108, 108, .15);
  color: #ff8585;
}
.field-tip i { margin-right: 6px; font-size: 13px }
@keyframes shake {
  0%, 100% { transform: translateX(0) }
  25%      { transform: translateX(-4px) }
  75%      { transform: translateX(4px) }
}

/* ---------- 记住密码 + 忘记密码 ---------- */
.form-extra {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin: 2px 0 16px;
  font-size: 12px;
}
.forgot-link {
  color: var(--color-primary);
  text-decoration: none;
  transition: opacity .2s;
}
.forgot-link:hover { opacity: .75; text-decoration: underline }

/* ---------- 登录按钮 ---------- */
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
.login-btn:hover {
  transform: translateY(-1px);
  box-shadow: 0 10px 22px rgba(64, 158, 255, .40), inset 0 1px 0 rgba(255, 255, 255, .25);
  background: linear-gradient(135deg, #4cabff 0%, #7e54c7 100%) !important;
}
.login-btn:active { transform: translateY(0) }

/* ---------- 分隔线 + 第三方登录 ---------- */
.divider {
  display: flex;
  align-items: center;
  margin: 20px 0 12px;
  color: var(--color-text-secondary);
  font-size: 11px;
  letter-spacing: .5px;
}
.divider::before, .divider::after {
  content: '';
  flex: 1;
  height: 1px;
  background: var(--color-border);
}
.divider span { padding: 0 12px }

.social-login {
  display: flex;
  justify-content: center;
  gap: 12px;
  margin-bottom: 16px;
}
.social-btn {
  width: 36px; height: 36px;
  display: flex; align-items: center; justify-content: center;
  border-radius: 50%;
  background: var(--bg-hover);
  border: 1px solid var(--color-border);
  color: var(--color-text-regular);
  font-size: 16px;
  cursor: pointer;
  transition: all .2s ease;
  text-decoration: none;
}
.social-btn:hover {
  color: var(--color-primary);
  border-color: var(--color-primary);
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(64, 158, 255, .18);
}

/* ---------- 底部 ---------- */
.copyright {
  margin: 0;
  text-align: center;
  font-size: 11px;
  color: var(--color-text-placeholder);
  letter-spacing: .3px;
}
.footer-link {
  color: var(--color-text-secondary);
  text-decoration: none;
  transition: color .2s;
}
.footer-link:hover { color: var(--color-primary) }

/* ---------- 主题切换按钮 ---------- */
.theme-toggle {
  position: absolute; top: 20px; right: 20px; z-index: 10;
  background-color: rgba(255, 255, 255, .9);
  border-color: rgba(0, 0, 0, .08);
  color: #555;
  box-shadow: 0 2px 6px rgba(0, 0, 0, .06);
}
.theme-toggle:hover { background-color: #fff; color: #1890ff }
html.theme-dark .theme-toggle {
  background-color: rgba(20, 20, 20, .85);
  border-color: rgba(255, 255, 255, .12);
  color: var(--color-warning);
}
html.theme-dark .theme-toggle:hover {
  background-color: rgba(20, 20, 20, 1); color: var(--color-warning);
}

/* ---------- 过渡 ---------- */
.fade-enter-active, .fade-leave-active { transition: opacity .25s ease; }
.fade-enter, .fade-leave-to { opacity: 0; }

/* ---------- 响应式 ---------- */
@media (max-width: 768px) {
  .login-card {
    flex-direction: column;
    min-height: 0;
  }
  .card-brand {
    flex: none;
    padding: 24px 24px 16px;
    border-right: none;
    border-bottom: 1px solid var(--color-border-light);
  }
  html.theme-dark .card-brand {
    border-bottom-color: var(--color-border);
  }
  .brand-features { display: none }
  .card-form { padding: 24px 24px 20px }
}
</style>