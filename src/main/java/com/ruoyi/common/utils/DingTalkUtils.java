package com.ruoyi.common.utils;

import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.nio.charset.StandardCharsets;

/**
 * 钉钉机器人告警工具
 * 文档 3.4 告警场景全部使用本工具发出
 */
@Slf4j
public class DingTalkUtils {

    public static boolean sendText(String webhook, String content) {
        return sendText(webhook, content, false);
    }

    /**
     * @param webhook   自定义Webhook地址
     * @param content   文本内容
     * @param atAll     是否@所有人
     */
    public static boolean sendText(String webhook, String content, boolean atAll) {
        if (webhook == null || webhook.isEmpty()) {
            log.warn("dingtalk webhook is empty, skip send");
            return false;
        }
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost httpPost = new HttpPost(webhook);
            RequestConfig requestConfig = RequestConfig.custom()
                    .setConnectTimeout(5_000)
                    .setSocketTimeout(5_000)
                    .build();
            httpPost.setConfig(requestConfig);
            httpPost.setHeader("Content-Type", "application/json;charset=utf-8");

            String json = "{"
                    + "\"msgtype\":\"text\","
                    + "\"text\":{\"content\":\"" + escape(content) + "\"},"
                    + "\"at\":{\"isAtAll\":" + atAll + "}"
                    + "}";

            httpPost.setEntity(new StringEntity(json, StandardCharsets.UTF_8));

            try (CloseableHttpResponse resp = httpClient.execute(httpPost)) {
                String result = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
                log.info("dingtalk response: {}", result);
                return result.contains("\"errcode\":0");
            }
        } catch (Exception e) {
            log.error("dingtalk send error", e);
            return false;
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }
}
