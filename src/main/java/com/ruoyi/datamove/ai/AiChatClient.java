package com.ruoyi.datamove.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.DefaultRoutePlanner;
import org.apache.http.impl.conn.DefaultSchemePortResolver;
import org.apache.http.util.EntityUtils;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * OpenAI 兼容协议的 chat completions 客户端
 *
 * <p>只做一件事: 把 system + user 提示词发出去, 取回模型输出的文本。
 * 失败一律抛异常, 由上层降级到本地规则解析 —— AI 是加速器, 不能是单点故障。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiChatClient {

    private final AiProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @return 模型输出的纯文本 (已去掉 ```json 代码围栏)
     */
    public String chat(String systemPrompt, String userPrompt) {
        if (!props.usable()) {
            throw new IllegalStateException("AI 未配置 (sync.ai.api-key 为空)");
        }
        String body = buildBody(systemPrompt, userPrompt);
        int timeout = props.getTimeoutMs() <= 0 ? 30000 : props.getTimeoutMs();
        try (CloseableHttpClient httpClient = newNoProxyHttpClient()) {
            HttpPost post = new HttpPost(props.getApiUrl());
            post.setConfig(RequestConfig.custom()
                    .setConnectTimeout(10_000)
                    .setSocketTimeout(timeout)
                    .build());
            post.setHeader("Content-Type", "application/json;charset=utf-8");
            post.setHeader("Authorization", "Bearer " + props.getApiKey().trim());
            post.setEntity(new StringEntity(body, StandardCharsets.UTF_8));

            try (CloseableHttpResponse resp = httpClient.execute(post)) {
                String result = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
                int code = resp.getStatusLine().getStatusCode();
                if (code < 200 || code >= 300) {
                    throw new RuntimeException("AI 服务返回 " + code + ": " + abbreviate(result));
                }
                return extractContent(result);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("调用 AI 服务失败: " + e.getMessage(), e);
        }
    }

    /**
     * AI 服务是直连公网的, 不走任何代理。
     * <p>坑: macOS 上开着系统代理/Clash 时, JDK 会把 socksProxyHost/socksProxyPort 写进系统属性
     * (本机 127.0.0.1:7897 之类), Socket 层连代理失败就报
     * "Can't connect to SOCKS proxy: Connection refused", 于是被降级成本地规则解析。
     * 这里两层都堵掉: 清掉 socks 属性(Socket 层) + 用不走代理的 RoutePlanner(HttpClient 层)。
     */
    private CloseableHttpClient newNoProxyHttpClient() {
        System.setProperty("java.net.useSystemProxies", "false");
        System.clearProperty("socksProxyHost");
        System.clearProperty("socksProxyPort");
        return HttpClients.custom()
                .setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE))
                .build();
    }

    private String buildBody(String systemPrompt, String userPrompt) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode root = objectMapper.createObjectNode();
            root.put("model", props.getModel());
            root.put("temperature", props.getTemperature());
            root.put("stream", false);
            com.fasterxml.jackson.databind.node.ArrayNode messages = root.putArray("messages");
            messages.addObject().put("role", "system").put("content", systemPrompt);
            messages.addObject().put("role", "user").put("content", userPrompt);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("构造 AI 请求失败: " + e.getMessage(), e);
        }
    }

    /** 取 choices[0].message.content, 顺手剥掉 ```json 围栏 */
    private String extractContent(String responseJson) {
        try {
            JsonNode root = objectMapper.readTree(responseJson);
            JsonNode choices = root.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                String content = choices.get(0).path("message").path("content").asText(null);
                if (content == null || content.trim().isEmpty()) {
                    // 部分兼容网关把内容放在 text 字段
                    content = choices.get(0).path("text").asText(null);
                }
                if (content != null) return stripFence(content.trim());
            }
            // openai 风格的错误体: {"error":{"message":"..."}}
            String err = root.path("error").path("message").asText(null);
            if (err != null && !err.isEmpty()) throw new RuntimeException("AI 服务报错: " + err);
            throw new RuntimeException("AI 返回内容为空");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("解析 AI 返回失败: " + abbreviate(responseJson), e);
        }
    }

    public static String stripFence(String s) {
        if (s == null) return "";
        String r = s.trim();
        if (r.startsWith("```")) {
            int nl = r.indexOf('\n');
            r = nl > 0 ? r.substring(nl + 1) : r.substring(3);
            int end = r.lastIndexOf("```");
            if (end >= 0) r = r.substring(0, end);
        }
        return r.trim();
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() > 200 ? one.substring(0, 200) + "..." : one;
    }
}
