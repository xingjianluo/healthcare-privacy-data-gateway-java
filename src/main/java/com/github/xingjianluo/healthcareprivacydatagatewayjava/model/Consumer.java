package com.github.xingjianluo.healthcareprivacydatagatewayjava.model;

/** 访问方（本地数据模拟的科研机构/企业）。 */
public record Consumer(
        String consumerId,
        String name,
        String organization
) {
}
