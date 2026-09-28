package com.github.xingjianluo.healthcareprivacydatagatewayjava.model;

/** 授权状态：有效期由 validFrom/validUntil 判定，撤回为显式状态。 */
public enum ConsentStatus {
    ACTIVE,
    REVOKED
}
