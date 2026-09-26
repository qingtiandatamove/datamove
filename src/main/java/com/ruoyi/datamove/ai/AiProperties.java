package com.ruoyi.datamove.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * AI 任务配置助手配置
 *
 * <p>接的是 OpenAI 兼容的 /chat/completions 协议, DeepSeek / 通义千问(兼容模式) / 火山方舟 / 智谱 /
 * 本地 Ollama 都能直接用, 换服务商最省事的写法是只改 provider(见 AiProvider)。
 *
 * <p>优先级: 显式写的 api-url / model &gt; provider 预设 &gt; 内置默认值(DeepSeek)。
 *
 * <p>没配 api-key 时不会报错, 助手自动降级为「本地规则解析」(AiRuleParser), 保证零配置也能用。
 */
@Data
@Component
@ConfigurationProperties(prefix = "sync.ai")
public class AiProperties {

    /** 总开关: false 时强制走本地规则解析 */
    private boolean enabled = true;

    /**
     * 服务商: deepseek / qwen / ark(火山方舟) / zhipu / ollama / custom
     * 配了它, api-url 与 model 会自动套用预设(自己手写的值优先)
     */
    private String provider = "";

    /** OpenAI 兼容的 chat completions 地址 */
    private String apiUrl = "https://api.deepseek.com/v1/chat/completions";

    /** API Key (建议用环境变量 AI_API_KEY 注入, 不要写死在配置文件里) */
    private String apiKey = "";

    /** 模型名称 */
    private String model = "deepseek-chat";

    /** 超时时间(毫秒): 自然语言解析属于交互操作, 30s 还不出结果就没必要等了 */
    private int timeoutMs = 30000;

    /** 采样温度: 解析配置要稳, 默认给低温度 */
    private double temperature = 0.1;

    public boolean usable() {
        return enabled && apiKey != null && !apiKey.trim().isEmpty()
                && apiUrl != null && !apiUrl.trim().isEmpty();
    }

    /** 绑定完成后按 provider 补默认值: 没手写的 api-url / model 才用预设覆盖 */
    @PostConstruct
    public void resolveProvider() {
        AiProvider p = AiProvider.of(provider);
        if (p == null) {
            // 没配 provider: 按 api-url 猜一家, 只用于界面展示, 不改任何配置
            this.providerEnum = AiProvider.detect(apiUrl);
            return;
        }
        this.providerEnum = p;
        if (isBlank(apiUrl) || AiProvider.DEEPSEEK.getDefaultApiUrl().equals(apiUrl.trim())) {
            if (!isBlank(p.getDefaultApiUrl())) apiUrl = p.getDefaultApiUrl();
        }
        if (isBlank(model) || AiProvider.DEEPSEEK.getDefaultModel().equals(model.trim())) {
            if (!isBlank(p.getDefaultModel())) model = p.getDefaultModel();
        }
    }

    /** 当前服务商(展示用), 永远不为 null */
    public AiProvider provider() {
        return providerEnum == null ? AiProvider.CUSTOM : providerEnum;
    }

    /** 展示名, 如「火山方舟」 */
    public String providerLabel() {
        return provider().getLabel();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** 解析后的服务商, 不参与配置绑定 */
    private transient AiProvider providerEnum;
}
