module.exports = {
  publicPath: process.env.NODE_ENV === 'production' ? './' : '/',
  outputDir: 'dist',
  assetsDir: 'static',
  lintOnSave: false,
  productionSourceMap: false,
  devServer: {
    port: 1024,
    host: '0.0.0.0',
    open: false,
    // 关闭编译错误覆盖层, 保留真正的运行时错误 (错误统一由 ElementUI Message 提示)
    client: {
      overlay: {
        errors: false,
        warnings: false,
        // 过滤无害的 ResizeObserver loop 报错 (Element UI 表格尺寸抖动引发)
        runtimeErrors: (error) => {
          if (error && /ResizeObserver loop/.test(String(error.message || error))) {
            return false
          }
          return true
        }
      },
      logging: 'warn',
      progress: false
    },
    proxy: {
      '/dev-api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
        ws: true,
        pathRewrite: { '^/dev-api': '' }
      }
    }
  }
}