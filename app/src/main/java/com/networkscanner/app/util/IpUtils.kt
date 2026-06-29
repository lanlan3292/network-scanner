// app/src/main/java/com/networkscanner/app/util/IpUtils.kt
package com.networkscanner.app.util

import java.net.InetAddress

object IpUtils {

    /**
     * 判断给定的前缀长度是否为大子网（小于 24 位）。
     */
    fun isLargeSubnet(prefix: Int): Boolean = prefix < 24

    /**
     * 将 IP 字符串转为 Long 值（按网络字节序）。
     */
    fun ipToLong(ip: String): Long {
        val parts = ip.split('.').map { it.toLong() }
        return (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
    }

    /**
     * 将 Long 值转为 IP 字符串。
     */
    fun longToIp(ip: Long): String {
        return "${(ip shr 24) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 8) and 0xFF}.${ip and 0xFF}"
    }

    /**
     * 根据网络地址和前缀长度，返回该子网的起始 IP（网络地址）和结束 IP（广播地址）。
     */
    fun getSubnetRange(networkIp: Long, prefix: Int): Pair<Long, Long> {
        val mask = if (prefix == 0) 0L else (-1L shl (32 - prefix))
        val start = networkIp and mask
        val end = start or (mask.inv())
        return start to end
    }

    /**
     * 从网络信息中提取 IP 和前缀，生成 /24 子网的范围（用于大子网默认扫描）。
     */
    fun getDefaultRangeForNetwork(networkInfo: com.networkscanner.app.data.NetworkInfo): Pair<Long, Long>? {
        val ip = networkInfo.ipAddress
        val prefix = networkInfo.networkPrefix
        // 取网络地址（将 IP 与掩码相与），然后使用 /24 掩码重新计算范围
        val networkLong = ipToLong(ip)
        val originalMask = if (prefix == 0) 0L else (-1L shl (32 - prefix))
        val networkStart = networkLong and originalMask
        // 使用 /24 前缀（固定 24）计算范围
        return getSubnetRange(networkStart, 24)
    }

    /**
     * 解析用户输入的 IP 范围字符串（支持 "192.168.1.1-192.168.1.254" 或 "192.168.1.0/24"）。
     * 返回起始和结束 IP 的 Long 值，若解析失败返回 null。
     */
    fun parseRange(input: String): Pair<Long, Long>? {
        val trimmed = input.trim()
        // 尝试 CIDR
        if (trimmed.contains('/')) {
            val parts = trimmed.split('/')
            if (parts.size == 2) {
                val ip = parts[0].trim()
                val prefix = parts[1].trim().toIntOrNull() ?: return null
                if (prefix !in 0..32) return null
                val ipLong = ipToLong(ip)
                return getSubnetRange(ipLong, prefix)
            }
        }
        // 尝试范围 "ip-ip"
        if (trimmed.contains('-')) {
            val parts = trimmed.split('-')
            if (parts.size == 2) {
                val startIp = parts[0].trim()
                val endIp = parts[1].trim()
                return ipToLong(startIp) to ipToLong(endIp)
            }
        }
        // 尝试单个 IP（只扫描该 IP）
        return try {
            val ipLong = ipToLong(trimmed)
            ipLong to ipLong
        } catch (_: Exception) {
            null
        }
    }
}