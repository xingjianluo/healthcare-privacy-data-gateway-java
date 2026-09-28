package com.github.xingjianluo.healthcareprivacydatagatewayjava.model;

/** 一次访问请求的输入：谁、以什么用途、访问哪位患者。 */
public record AccessRequest(
        String patientId,
        String consumerId,
        AccessPurpose purpose
) {
}
