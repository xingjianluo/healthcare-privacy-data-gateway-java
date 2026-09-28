package com.github.xingjianluo.healthcareprivacydatagatewayjava.model;

/**
 * 可区分的拒绝原因码。不同失败场景必须返回不同的码，禁止合并成同一种报错。
 */
public enum DenialReason {
    CONSUMER_NOT_FOUND,
    PATIENT_NOT_FOUND,
    NO_CONSENT,
    CONSENT_REVOKED,
    CONSENT_EXPIRED,
    PURPOSE_OUT_OF_SCOPE
}
