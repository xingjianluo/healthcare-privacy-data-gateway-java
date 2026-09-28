package com.github.xingjianluo.healthcareprivacydatagatewayjava.model;

/** 一次访问请求。 */
public record AccessRequest(
        String accessorId,
        String patientId,
        Purpose purpose
) {
}
