<template>
  <el-dialog title="Cron 表达式生成器" :visible.sync="show" width="720px" top="6vh" append-to-body>
    <el-alert v-if="unsupported" type="warning" :closable="false" show-icon style="margin-bottom:12px"
      title="当前表达式包含本生成器不支持的语法 (如 年域 / L-W-# 组合), 直接保存将按下方预览值覆盖" />

    <el-tabs v-model="activeTab">
      <el-tab-pane v-for="t in tabs" :key="t.key" :name="t.key">
        <span slot="label">{{ t.label }}</span>

        <div class="cron-body">
          <!-- 每一 X -->
          <div class="cron-row">
            <el-radio :label="keyOf(t, '*')" :value="sel[t.key]" @input="v => setType(t, v)">{{ t.every }}</el-radio>
          </div>

          <!-- 不指定 (仅 天/周) -->
          <div class="cron-row" v-if="has(t, '?')">
            <el-radio :label="keyOf(t, '?')" :value="sel[t.key]" @input="v => setType(t, v)">不指定</el-radio>
          </div>

          <!-- 周期: 从 A 到 B -->
          <div class="cron-row" v-if="has(t, 'range')">
            <el-radio :label="keyOf(t, 'range')" :value="sel[t.key]" @input="v => setType(t, v)">周期从</el-radio>
            <el-input-number v-model="f[t.key].from" :min="t.min" :max="t.max" size="mini"
              :disabled="f[t.key].type !== 'range'" controls-position="right" style="width:90px;margin:0 6px" />
            <span>到</span>
            <el-input-number v-model="f[t.key].to" :min="t.min" :max="t.max" size="mini"
              :disabled="f[t.key].type !== 'range'" controls-position="right" style="width:90px;margin:0 6px" />
            <span>{{ t.unit }}</span>
          </div>

          <!-- 间隔: 从 A 开始每隔 B -->
          <div class="cron-row" v-if="has(t, 'interval')">
            <el-radio :label="keyOf(t, 'interval')" :value="sel[t.key]" @input="v => setType(t, v)">每隔</el-radio>
            <el-input-number v-model="f[t.key].step" :min="1" :max="t.max" size="mini"
              :disabled="f[t.key].type !== 'interval'" controls-position="right" style="width:90px;margin:0 6px" />
            <span>{{ t.unit }}执行 从</span>
            <el-input-number v-model="f[t.key].start" :min="t.min" :max="t.max" size="mini"
              :disabled="f[t.key].type !== 'interval'" controls-position="right" style="width:90px;margin:0 6px" />
            <span>{{ t.unit }}开始</span>
          </div>

          <!-- 具体值多选 -->
          <div class="cron-row" v-if="has(t, 'specific')">
            <el-radio :label="keyOf(t, 'specific')" :value="sel[t.key]" @input="v => setType(t, v)">具体{{ t.unit }}(可多选)</el-radio>
            <el-select v-model="f[t.key].specific" multiple collapse-tags size="small"
              :disabled="f[t.key].type !== 'specific'" placeholder="请选择" style="width:300px;margin-left:6px">
              <el-option v-for="o in optionsOf(t)" :key="o.value" :label="o.label" :value="o.value" />
            </el-select>
          </div>

          <!-- 天: 每月最后一天 -->
          <div class="cron-row" v-if="has(t, 'L')">
            <el-radio :label="keyOf(t, 'L')" :value="sel[t.key]" @input="v => setType(t, v)">本月最后一天</el-radio>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>

    <div class="cron-footer">
      <span class="cron-preview">{{ expr }}</span>
      <el-button type="primary" size="small" @click="onSave">保 存</el-button>
      <el-button size="small" @click="show = false">关 闭</el-button>
    </div>
  </el-dialog>
</template>

<script>
/* 6 位 cron (Spring 格式: 秒 分 时 天 月 周), 对应后端 CronExpression */
const WEEK_NAMES = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT']
const WEEK_LABELS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

function mkField (min, max) {
  return { type: '*', start: min, step: 1, from: min, to: max, specific: [] }
}

export default {
  name: 'CronPicker',
  props: {
    visible: { type: Boolean, default: false },
    value: { type: String, default: '' }
  },
  data () {
    const year = new Date().getFullYear()
    return {
      activeTab: 'sec',
      unsupported: false,
      tabs: [
        { key: 'sec',   label: '秒', unit: '秒',   every: '每一秒钟', min: 0,  max: 59,        types: ['*', 'range', 'interval', 'specific'] },
        { key: 'min',   label: '分', unit: '分钟', every: '每一分钟', min: 0,  max: 59,        types: ['*', 'range', 'interval', 'specific'] },
        { key: 'hour',  label: '时', unit: '小时', every: '每一小时', min: 0,  max: 23,        types: ['*', 'range', 'interval', 'specific'] },
        { key: 'day',   label: '天', unit: '日',   every: '每一天',   min: 1,  max: 31,        types: ['*', '?', 'range', 'interval', 'specific', 'L'] },
        { key: 'month', label: '月', unit: '月',   every: '每一月',   min: 1,  max: 12,        types: ['*', 'range', 'interval', 'specific'], month: true },
        { key: 'week',  label: '周', unit: '周',   every: '不限星期', min: 1,  max: 7,         types: ['*', '?', 'specific'], week: true }
      ],
      f: {
        sec: Object.assign(mkField(0, 59), { type: 'specific', specific: ['0'] }),
        min: Object.assign(mkField(0, 59), { type: 'specific', specific: ['0'] }),
        hour: mkField(0, 23),
        day: mkField(1, 31), month: mkField(1, 12), week: mkField(1, 7),
        year: mkField(year, year + 10)
      }
    }
  },
  computed: {
    show: {
      get () { return this.visible },
      set (v) { this.$emit('update:visible', v) }
    },
    /* radio 的 v-model 值: 加 key 前缀避免不同 tab 的 label 冲突 */
    sel () {
      const s = {}
      for (const t of this.tabs) {
        s[t.key] = this.f[t.key].type === '?' ? t.key + ':?' : t.key + ':' + this.f[t.key].type
      }
      return s
    },
    expr () {
      const g = k => this.gen(k)
      return [g('sec'), g('min'), g('hour'), g('day'), g('month'), g('week')].join(' ')
    }
  },
  watch: {
    visible (v) {
      if (v) {
        this.unsupported = false
        this.parse(this.value)
        this.activeTab = 'sec'
      }
    }
  },
  methods: {
    has (t, type) { return t.types.includes(type) },
    keyOf (t, type) { return t.key + ':' + type },

    /* radio 选中: 写回字段类型; 切换 天/周 时自动互斥 (一方指定具体值, 另一方置为不指定) */
    setType (t, v) {
      const type = String(v).split(':')[1]
      this.f[t.key].type = type
      if (t.key === 'day') {
        this.f.week.type = type === '?' ? '*' : '?'
      } else if (t.key === 'week') {
        this.f.day.type = type === '?' ? '*' : '?'
      }
    },

    optionsOf (t) {
      if (t.week) {
        return WEEK_NAMES.map((n, i) => ({ value: n, label: WEEK_LABELS[i] }))
      }
      if (t.month) {
        return Array.from({ length: 12 }, (_, i) => ({ value: String(i + 1), label: (i + 1) + ' 月' }))
      }
      const arr = []
      for (let i = t.min; i <= t.max; i++) arr.push({ value: String(i), label: String(i) })
      return arr
    },

    gen (key) {
      const f = this.f[key]
      switch (f.type) {
        case '*': return '*'
        case '?': return '?'
        case 'L': return 'L'
        case 'range': return Math.min(f.from, f.to) + '-' + Math.max(f.from, f.to)
        case 'interval': return f.start + '/' + f.step
        case 'specific': {
          const t = this.tabs.find(x => x.key === key)
          const arr = [...f.specific].sort((a, b) => t.week
            ? WEEK_NAMES.indexOf(a) - WEEK_NAMES.indexOf(b)
            : Number(a) - Number(b))
          return arr.length ? arr.join(',') : '*'
        }
        default: return '*'
      }
    },

    /** 重置为安全默认: 秒=0 分=0 (整点整分执行)。
     *  若默认「秒=每一秒」, 用户只配置了时/天等页签时生成 `* ... ` 表达式,
     *  保存后下一秒就被调度执行 —— 与「到点才执行」的预期严重不符 */
    resetDefault () {
      for (const t of this.tabs) {
        const f = this.f[t.key]
        f.type = '*'; f.start = t.min; f.step = 1; f.from = t.min; f.to = t.max; f.specific = []
      }
      this.f.sec.type = 'specific'; this.f.sec.specific = ['0']
      this.f.min.type = 'specific'; this.f.min.specific = ['0']
    },

    /** 打开时按现有表达式回填各 tab; 识别不了的置 unsupported 提示 */
    parse (expr) {
      const parts = (expr || '').trim().split(/\s+/)
      this.resetDefault()
      if (!expr || parts.length < 6) return
      const map = { sec: parts[0], min: parts[1], hour: parts[2], day: parts[3], month: parts[4], week: parts[5] }
      if (parts.length > 6) this.unsupported = true   // 年域: Spring 不支持

      for (const t of this.tabs) {
        const raw = (map[t.key] || '*').trim().toUpperCase()
        const f = this.f[t.key]
        if (raw === '*' || raw === '?') { f.type = raw === '?' && !t.types.includes('?') ? '*' : raw; continue }
        if (raw === 'L') { if (t.types.includes('L')) { f.type = 'L'; continue } this.unsupported = true; continue }
        let m
        if ((m = raw.match(/^(\*|\d+)\/(\d+)$/))) {                 // 间隔: A/B 或 */B
          f.type = 'interval'
          f.start = m[1] === '*' ? t.min : Math.max(t.min, Math.min(t.max, Number(m[1])))
          f.step = Math.max(1, Number(m[2]))
          continue
        }
        if ((m = raw.match(/^(\d+)-(\d+)$/))) {                     // 周期: A-B
          if (t.types.includes('range')) {
            f.type = 'range'; f.from = Number(m[1]); f.to = Number(m[2]); continue
          }
          this.unsupported = true; continue
        }
        if ((m = raw.match(/^([A-Z]{3})-([A-Z]{3})$/))) {           // 周名区间 MON-FRI → 多选
          const i1 = WEEK_NAMES.indexOf(m[1]); const i2 = WEEK_NAMES.indexOf(m[2])
          if (t.week && i1 >= 0 && i2 >= 0) {
            f.type = 'specific'
            f.specific = WEEK_NAMES.slice(Math.min(i1, i2), Math.max(i1, i2) + 1)
            continue
          }
          this.unsupported = true; continue
        }
        if (/^[0-9A-Z,]+$/.test(raw)) {                             // 具体值列表
          if (t.types.includes('specific')) {
            const items = raw.split(',')
            const valid = items.every(it => t.week
              ? WEEK_NAMES.includes(it)
              : /^\d+$/.test(it) && Number(it) >= t.min && Number(it) <= t.max)
            if (valid && items.length) {
              f.type = 'specific'; f.specific = items; continue
            }
          }
          this.unsupported = true; continue
        }
        this.unsupported = true
      }
    },

    onSave () {
      this.$emit('input', this.expr)
      this.$emit('change', this.expr)
      this.show = false
    }
  }
}
</script>

<style scoped>
.cron-body { padding: 6px 12px; }
.cron-row { display: flex; align-items: center; margin-bottom: 16px; font-size: 14px; }
.cron-row .el-radio { margin-right: 10px; }
.cron-row span { color: #606266; }
.cron-footer { display: flex; align-items: center; justify-content: flex-end; padding-top: 4px; border-top: 1px dashed #dcdfe6; }
.cron-preview { flex: 1; font-family: Menlo, Consolas, monospace; font-size: 16px; font-weight: bold; color: #409eff; }
</style>
