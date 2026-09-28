package com.github.xingjianluo.healthcareprivacydatagatewayjava.model;

/**
 * 访问被拒绝的原因。不同失败原因使用不同的枚举值，
 * 调用方与测试可以稳定地区分拒绝类型。
 */
public enum DenyReason {
    /** 访问方在系统中不存在 / 未登记 */
    UNKNOWN_ACCESSOR,
    /** 患者不存在或没有任何匹配授权 */
    NO_GRANT,
    /** 授权已过期 */
    GRANT_EXPIRED,
    /** 授权已被患者撤回 */
    GRANT_REVOKED,
    /** 用途不在授权允许范围内 */
    PURPOSE_OUT_OF_SCOPE
}
