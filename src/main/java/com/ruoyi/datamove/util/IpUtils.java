package com.ruoyi.datamove.util;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IP / 客户端信息解析工具
 *
 * <p>登录日志要记「谁、什么时候、从哪个 IP、什么地点、用什么浏览器登录的」, 其中:
 *   - 真实 IP: 必须兼容 nginx 反代 (否则全部记成 127.0.0.1)
 *   - 地点: 走在线 IP 库 (ip-api.com), 带缓存 + 超时 + 失败兜底 —— 解析失败只影响「地点」这一列,
 *     绝不能拖慢或阻断登录
 *   - 浏览器 / 操作系统: 从 UA 正则硬解, 不引第三方依赖
 *
 * <p>注意: 内网/保留地址不查在线库, 直接判为「局域网 / 本机」, 省一次无谓的外网请求。
 */
@Slf4j
public final class IpUtils {

    private IpUtils() {
    }

    /** 反向代理透传真实 IP 的常用头, 按顺序取第一个有效值 */
    private static final String[] IP_HEADERS = {
            "X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP", "WL-Proxy-Client-IP", "HTTP_CLIENT_IP"
    };

    /**
     * 在线 IP 归属地数据源 (按序尝试, 第一个解析成功的就用)
     *
     * <p>都是免费公开接口且有速率限制, 所以结果一律缓存; 全部不通时地点留空, 页面显示「未知」。
     * 想改成离线库(如 ip2region)时, 只需替换 resolveLocation 的实现即可。
     */
    private static final String[] PROVIDERS = {
            "http://ip-api.com/json/%s?lang=zh-CN&fields=status,country,regionName,city",
            "https://ip.useragentinfo.com/json?ip=%s"
    };

    /** 各数据源的字段命名不一样, 这里按序取第一个非空值拼出「国家 省 市」 */
    private static final String[][] LOCATION_FIELDS = {
            {"country", "regionName", "city"},
            {"country", "province", "city"}
    };

    /** 地点缓存: IP -> 地点 (解析成功才缓存) */
    private static final Map<String, String> LOCATION_CACHE = new ConcurrentHashMap<>();

    /** 失败缓存: IP -> 失败时间戳, 5 分钟内不再重试 (服务器无外网时不至于每次登录都卡超时) */
    private static final Map<String, Long> FAIL_CACHE = new ConcurrentHashMap<>();
    private static final long FAIL_TTL = 5 * 60 * 1000L;
    private static final int CACHE_MAX = 10000;
    private static final int TIMEOUT_MS = 1500;

    /**
     * 取真实客户端 IP (兼容 nginx / 网关)
     *
     * @param request 当前请求, 允许为 null (定时任务等非 Web 上下文)
     */
    public static String clientIp(HttpServletRequest request) {
        if (request == null) return "";
        for (String header : IP_HEADERS) {
            String ip = request.getHeader(header);
            if (StrUtil.isNotBlank(ip) && !"unknown".equalsIgnoreCase(ip)) {
                int idx = ip.indexOf(',');
                return idx > 0 ? ip.substring(0, idx).trim() : ip.trim();
            }
        }
        return request.getRemoteAddr();
    }

    /** 是否回环地址 (本机) —— 含 IPv6 的 0:0:0:0:0:0:0:1 / ::1 */
    private static boolean isLoopback(String ip) {
        String s = ip.trim();
        return "127.0.0.1".equals(s) || "localhost".equalsIgnoreCase(s)
                || "::1".equals(s) || "0:0:0:0:0:0:0:1".equals(s);
    }

    /** 是否内网 / 保留地址 —— 这类 IP 查在线库没有意义 */
    public static boolean isInternal(String ip) {
        if (StrUtil.isBlank(ip)) return true;
        String s = ip.trim();
        if (isLoopback(s)) return true;
        if (s.startsWith("10.") || s.startsWith("192.168.")) return true;
        if (s.startsWith("172.")) {
            String[] p = s.split("\\.");
            if (p.length >= 2) {
                try {
                    int second = Integer.parseInt(p[1]);
                    if (second >= 16 && second <= 31) return true;   // 172.16.0.0 ~ 172.31.255.255
                } catch (NumberFormatException ignored) {
                    // 不是 172.x.x.x 的正规写法, 按公网处理
                }
            }
        }
        if (s.startsWith("169.254.")) return true;                    // 链路本地
        return false;
    }

    /**
     * 解析 IP 归属地, 形如「中国 广东 深圳」; 解析不出来返回空串 (页面显示为 -)
     *
     * <p>内网地址返回「局域网」/「本机」, 不发起外网请求。
     */
    public static String resolveLocation(String ip) {
        if (StrUtil.isBlank(ip)) return "";
        String key = ip.trim();
        if (isInternal(key)) {
            return isLoopback(key) ? "本机" : "局域网";
        }
        String cached = LOCATION_CACHE.get(key);
        if (cached != null) return cached;
        Long failedAt = FAIL_CACHE.get(key);
        if (failedAt != null && System.currentTimeMillis() - failedAt < FAIL_TTL) return "";

        String location = "";
        // 多个在线库依次尝试: 不同网络环境(内网/海外/云主机)可达性不一样, 一个不通就换下一个
        for (int i = 0; i < PROVIDERS.length && StrUtil.isBlank(location); i++) {
            try {
                String body = HttpUtil.get(String.format(PROVIDERS[i], key), TIMEOUT_MS);
                location = parseLocation(i, body);
            } catch (Exception e) {
                // 解析失败只影响「地点」一列, 不抛给调用方
                log.debug("[IP] 归属地解析失败 ip={} provider={} err={}", key, i, e.getMessage());
            }
        }

        if (StrUtil.isNotBlank(location)) {
            if (LOCATION_CACHE.size() < CACHE_MAX) LOCATION_CACHE.put(key, location);
        } else {
            FAIL_CACHE.put(key, System.currentTimeMillis());
        }
        return location;
    }

    /** 解析某个数据源的响应体: 拼出「国家 省 市」(去重, 深圳市/深圳这类重复只留一个) */
    private static String parseLocation(int providerIdx, String body) {
        if (StrUtil.isBlank(body) || !JSONUtil.isTypeJSON(body)) return "";
        JSONObject jo = JSONUtil.parseObj(body);
        // ip-api 失败时返回 {"status":"fail","message":...}, 必须判状态
        if (providerIdx == 0 && !"success".equals(jo.getStr("status"))) return "";
        Set<String> parts = new LinkedHashSet<>();
        for (String field : LOCATION_FIELDS[providerIdx]) {
            String v = StrUtil.trimToNull(jo.getStr(field));
            if (v != null) parts.add(v);
        }
        return String.join(" ", parts);
    }

    /** 从 UA 里认浏览器 (够用即可, 不追求精确版本) */
    public static String browserOf(String ua) {
        if (StrUtil.isBlank(ua)) return "";
        String u = ua.toLowerCase();
        if (u.contains("postmanruntime")) return "Postman";
        if (u.contains("apifox")) return "Apifox";
        if (u.contains("edg/")) return "Edge " + majorVersion(u, "edg/");
        if (u.contains("opr/") || u.contains("opera")) return "Opera " + majorVersion(u, "opr/");
        if (u.contains("firefox/")) return "Firefox " + majorVersion(u, "firefox/");
        if (u.contains("chrome/")) return "Chrome " + majorVersion(u, "chrome/");
        if (u.contains("safari/")) return "Safari " + majorVersion(u, "version/");
        if (u.contains("msie ") || u.contains("trident/")) return "IE";
        return "未知";
    }

    /** 从 UA 里认操作系统 */
    public static String osOf(String ua) {
        if (StrUtil.isBlank(ua)) return "";
        String u = ua.toLowerCase();
        if (u.contains("windows nt 10.0")) return "Windows 10/11";
        if (u.contains("windows nt 6.3")) return "Windows 8.1";
        if (u.contains("windows nt 6.2")) return "Windows 8";
        if (u.contains("windows nt 6.1")) return "Windows 7";
        if (u.contains("windows")) return "Windows";
        if (u.contains("iphone") || u.contains("ipad") || u.contains("ipod")) return "iOS";
        if (u.contains("android")) return "Android";
        if (u.contains("mac os x") || u.contains("macintosh")) return "macOS";
        if (u.contains("linux")) return "Linux";
        return "未知";
    }

    /** 取主版本号: chrome/120.0.6099 → 120 */
    private static String majorVersion(String lowerUa, String token) {
        int i = lowerUa.indexOf(token);
        if (i < 0) return "";
        String tail = lowerUa.substring(i + token.length());
        int dot = tail.indexOf('.');
        String v = dot > 0 ? tail.substring(0, dot) : tail;
        return v.replaceAll("[^0-9]", "");
    }
}
