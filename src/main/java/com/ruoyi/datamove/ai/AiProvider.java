package com.ruoyi.datamove.ai;

import lombok.Getter;

import java.util.Locale;

/**
 * AI 服务商预设 —— 全都是 OpenAI 兼容的 /chat/completions 协议
 *
 * <p>作用只有一个: 少写配置。配置里写 sync.ai.provider=ark, api-url 与 model 就会自动
 * 套用火山方舟的默认值; 你手写了 api-url / model 就以你写的为准, 不会被预设覆盖。
 *
 * <p>新增一家服务商: 在这里加一个枚举值即可, 不用改其他代码。
 */
@Getter
public enum AiProvider {

    DEEPSEEK("deepseek", "DeepSeek",
            "https://api.deepseek.com/v1/chat/completions", "deepseek-chat",
            "模型名: deepseek-chat / deepseek-reasoner"),

    QWEN("qwen", "通义千问",
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "qwen-plus",
            "模型名: qwen-plus / qwen-max / qwen-turbo"),

    /** 火山方舟(豆包): 鉴权用方舟的 API Key, model 填控制台「接入点 ID」(ep-xxxx) 或 doubao 模型名 */
    ARK("ark", "火山方舟",
            "https://ark.cn-beijing.volces.com/api/v3/chat/completions", "doubao-pro-32k",
            "model 填接入点ID(ep-xxxxxxxx) 或 doubao-pro-32k 等模型名"),

    ZHIPU("zhipu", "智谱 GLM",
            "https://open.bigmodel.cn/api/paas/v4/chat/completions", "glm-4-flash",
            "模型名: glm-4-flash / glm-4-plus"),

    OLLAMA("ollama", "本地 Ollama",
            "http://127.0.0.1:11434/v1/chat/completions", "qwen2.5",
            "本地部署不校验 Key, api-key 随便填个非空字符串即可"),

    CUSTOM("custom", "自定义(OpenAI 兼容)", "", "",
            "自己填 sync.ai.api-url 与 sync.ai.model");

    private final String code;
    private final String label;
    private final String defaultApiUrl;
    private final String defaultModel;
    private final String hint;

    AiProvider(String code, String label, String defaultApiUrl, String defaultModel, String hint) {
        this.code = code;
        this.label = label;
        this.defaultApiUrl = defaultApiUrl;
        this.defaultModel = defaultModel;
        this.hint = hint;
    }

    /** 按 code 匹配(忽略大小写/空格), 认不出来返回 null */
    public static AiProvider of(String code) {
        if (code == null || code.trim().isEmpty()) return null;
        String c = code.trim().toLowerCase(Locale.ROOT);
        for (AiProvider p : values()) {
            if (p.code.equals(c) || p.label.equals(code.trim())) return p;
        }
        // 常见的中文/别名写法
        if (c.contains("方舟") || c.contains("ark") || c.contains("volc") || c.contains("doubao")) return ARK;
        if (c.contains("deepseek")) return DEEPSEEK;
        if (c.contains("千问") || c.contains("qwen") || c.contains("dashscope")) return QWEN;
        if (c.contains("智谱") || c.contains("glm") || c.contains("bigmodel")) return ZHIPU;
        if (c.contains("ollama")) return OLLAMA;
        return null;
    }

    /** 没显式配 provider 时, 按 api-url 猜一家, 仅用于界面展示 */
    public static AiProvider detect(String apiUrl) {
        if (apiUrl == null || apiUrl.trim().isEmpty()) return CUSTOM;
        String u = apiUrl.toLowerCase(Locale.ROOT);
        if (u.contains("volces.com") || u.contains("ark.") || u.contains("doubao")) return ARK;
        if (u.contains("deepseek")) return DEEPSEEK;
        if (u.contains("dashscope") || u.contains("qwen")) return QWEN;
        if (u.contains("bigmodel") || u.contains("glm")) return ZHIPU;
        if (u.contains("11434") || u.contains("ollama")) return OLLAMA;
        return CUSTOM;
    }
}
