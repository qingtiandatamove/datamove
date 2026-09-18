package com.ruoyi.common.utils;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * AES 对称加密工具类
 * 用于数据源密码加密存储 (CBC 模式, PKCS5Padding)
 *
 * 说明: 这是文档 2.1 技术栈中要求的"数据库密码加密存储"
 */
@Slf4j
public class AesUtils {

    private static final String KEY_ALGORITHM   = "AES";
    private static final String DEFAULT_CIPHER = "AES/CBC/PKCS5Padding";

    private static final String KEY;
    private static final IvParameterSpec IV;

    static {
        String k = "datamove16816888"; // 16位
        if (k.length() != 16) {
            // 自动补齐/截取
            StringBuilder sb = new StringBuilder(k);
            while (sb.length() < 16) sb.append("0");
            k = sb.substring(0, 16);
        }
        KEY = k;
        IV  = new IvParameterSpec(KEY.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 加密
     */
    public static String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return plain;
        }
        try {
            SecretKeySpec keySpec = new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), KEY_ALGORITHM);
            Cipher cipher = Cipher.getInstance(DEFAULT_CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, IV);
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            log.error("AES encrypt error", e);
            throw new RuntimeException("AES加密失败", e);
        }
    }

    /**
     * 解密
     */
    public static String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isEmpty()) {
            return cipherText;
        }
        try {
            SecretKeySpec keySpec = new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), KEY_ALGORITHM);
            Cipher cipher = Cipher.getInstance(DEFAULT_CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, IV);
            byte[] raw = Base64.getDecoder().decode(cipherText);
            return new String(cipher.doFinal(raw), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("AES decrypt error: {}", cipherText, e);
            return cipherText;
        }
    }

    public static void main(String[] args) {
        String enc = encrypt("root123");
        System.out.println("encrypted=" + enc);
        System.out.println("decrypted=" + decrypt(enc));
    }
}
