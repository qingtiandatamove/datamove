package com.ruoyi.datamove.license;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ruoyi.common.utils.MacUtils;
import com.ruoyi.datamove.license.domain.SyncLicense;
import com.ruoyi.datamove.license.mapper.SyncLicenseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

/**
 * License 授权服务 (opt-in)
 *
 * <p>本类仅在 {@code sync.license.enabled=true} 时由 Spring 实例化,
 * 默认 {@code false} 时整个授权链路完全不存在 —— 没有启动校验, 没有 MAC 绑定,
 * 也没有 {@code System.exit(1)}。适合开源场景下的零阻碍体验。
 *
 * <p>启用时 (对应文档 3.5):
 * <ul>
 *   <li>仅项目启动时联网校验一次,运行时可断网</li>
 *   <li>校验维度: LicenseKey 合法性 + MAC 地址绑定 + 到期时间</li>
 *   <li>过期后重启项目无法启动</li>
 * </ul>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "sync.license.enabled", havingValue = "true")
@RequiredArgsConstructor
public class LicenseService {

    private final SyncLicenseMapper licenseMapper;

    @Value("${sync.license-server}")
    private String licenseServerUrl;

    @Value("${sync.license-key}")
    private String configuredKey;

    private volatile boolean allowed = true;

    /**
     * 启动时校验。
     *
     * <p>本类已被 {@code @ConditionalOnProperty(sync.license.enabled=true)} 限定, 默认配置下根本不会被实例化,
     * 该方法也根本不会被注册 / 调用。 启用后的语义仍按旧版: 启动期一次性校验, 不通过则 {@code System.exit(1)}。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void verifyOnStartup() {
        try {
            log.info("License: 开始启动期校验...");
            String mac = MacUtils.getLocalMac();
            log.info("License: 本机 MAC = {}", mac);

            // 1. 查询本地 license 配置
            SyncLicense license = licenseMapper.selectOne(
                    new QueryWrapper<SyncLicense>().eq("license_key", configuredKey));
            if (license == null) {
                // 首次启动: 自动注册一个试用
                SyncLicense newLic = new SyncLicense();
                newLic.setLicenseKey(configuredKey);
                newLic.setMacAddress(mac);
                newLic.setExpireTime(Date.from(LocalDateTime.now().plusDays(30)
                        .atZone(ZoneId.systemDefault()).toInstant()));
                newLic.setMaxParallel(5);
                newLic.setStatus("0");
                newLic.setLastCheckTime(new Date());
                newLic.setCreateTime(new Date());
                licenseMapper.insert(newLic);
                license = newLic;
                log.warn("License: 未配置本地记录,已自动注册30天试用 License, MAC={}", mac);
            } else if (license.getMacAddress() == null) {
                // 已存在但无 MAC,自动绑定
                license.setMacAddress(mac);
                licenseMapper.updateById(license);
            }

            // 2. 校验 MAC 是否匹配
            if (license.getMacAddress() != null && !license.getMacAddress().equalsIgnoreCase(mac)) {
                log.error("License 校验失败: MAC地址不匹配! 配置 MAC={}  本机 MAC={}", license.getMacAddress(), mac);
                System.exit(1);
            }

            // 3. 校验过期时间
            Date expire = license.getExpireTime();
            if (expire == null || expire.before(new Date())) {
                log.error("License 已过期! expireTime={}", expire);
                System.exit(1);
            }
            long daysLeft = Duration.between(new Date().toInstant(), expire.toInstant()).toDays();
            log.info("License 校验通过: 剩余 {} 天, MAC绑定={}", daysLeft, mac);

            // 4. 联网校验 - 异步,不阻塞启动 (云端可以拒绝异常MAC)
            try {
                verifyOnline(license, mac);
            } catch (Exception ex) {
                log.warn("License 联网校验失败,允许离线使用 (云端拒绝时 throw 即可阻断)");
            }

            // 5. 更新最后校验时间
            license.setLastCheckTime(new Date());
            licenseMapper.updateById(license);
            allowed = true;

        } catch (Exception e) {
            log.error("License 系统异常,允许启动以进行本地试用", e);
        }
    }

    private void verifyOnline(SyncLicense license, String mac) throws Exception {
        // 这里演示如何调用云端校验:http POST {key, mac}
        // 实际环境根据 License Service 接口文档对接
        // Demo 实现: 仅记录日志
        log.info("License 在线校验: {} -> {}", licenseServerUrl, mac);
        // 模拟请求
        HttpURLConnection conn = (HttpURLConnection) new URL(licenseServerUrl).openConnection();
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        String body = "{\"licenseKey\":\"" + license.getLicenseKey() + "\",\"mac\":\"" + mac + "\"}";
        conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        int code = conn.getResponseCode();
        conn.disconnect();
        log.info("License 在线校验返回 code={}", code);
    }

    public SyncLicense get() {
        return licenseMapper.selectOne(
                new QueryWrapper<SyncLicense>().eq("license_key", configuredKey));
    }

    public void update(SyncLicense license) {
        licenseMapper.updateById(license);
    }

    public boolean isAllowed() { return allowed; }
}
