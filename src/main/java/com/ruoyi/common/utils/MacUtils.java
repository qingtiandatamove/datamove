package com.ruoyi.common.utils;

import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * MAC 地址工具 - 用于 License 一机一绑
 */
@Slf4j
public class MacUtils {

    /**
     * 获取本机第一张物理网卡的MAC地址
     */
    public static String getLocalMac() {
        try {
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface network = networkInterfaces.nextElement();
                if (network.isLoopback() || network.isVirtual() || !network.isUp()) {
                    continue;
                }
                byte[] macBytes = network.getHardwareAddress();
                if (macBytes != null && macBytes.length > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < macBytes.length; i++) {
                        sb.append(String.format("%02X", macBytes[i]));
                        if (i < macBytes.length - 1) sb.append(":");
                    }
                    return sb.toString();
                }
            }
        } catch (Exception e) {
            log.error("get mac error", e);
        }
        return "UNKNOWN-MAC";
    }

    /**
     * 获取本机 IP
     */
    public static String getLocalIp() {
        try {
            Enumeration<NetworkInterface> allNetInterfaces = NetworkInterface.getNetworkInterfaces();
            while (allNetInterfaces.hasMoreElements()) {
                NetworkInterface network = allNetInterfaces.nextElement();
                if (network.isLoopback() || network.isVirtual() || !network.isUp()) continue;
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress inet = addresses.nextElement();
                    if (!inet.isLoopbackAddress() && inet.getHostAddress().indexOf(":") == -1) {
                        return inet.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            log.error("get ip error", e);
        }
        return "127.0.0.1";
    }
}
