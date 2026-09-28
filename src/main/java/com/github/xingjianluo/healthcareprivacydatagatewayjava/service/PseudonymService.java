package com.github.xingjianluo.healthcareprivacydatagatewayjava.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 假名生成。
 *
 * <p>口径：{@code pseudonym = "PSN-" + SHA256(systemPepper | accessorId | patientId)} 的前 32 个十六进制字符。
 * <ul>
 *   <li>同一访问方 + 同一患者：输入完全一致，假名稳定且可复现（幂等）；</li>
 *   <li>不同访问方：accessorId 作为键控材料的一部分参与哈希，得到的假名互不相同，
 *       仅凭返回内容无法跨访问方关联同一患者；</li>
 *   <li>单向哈希，返回结果中不暴露真实 patientId。</li>
 * </ul>
 * systemPepper 为本机模拟的固定域分隔串，使本系统输出与系统外同名ID的哈希不可直接对照。
 */
public class PseudonymService {

    private static final String SYSTEM_PEPPER = "healthcare-privacy-gateway/v1";
    private static final int HEX_LENGTH = 32;

    public String pseudonymFor(String accessorId, String patientId) {
        String material = SYSTEM_PEPPER + "|" + accessorId + "|" + patientId;
        return "PSN-" + sha256Hex(material).substring(0, HEX_LENGTH);
    }

    static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
