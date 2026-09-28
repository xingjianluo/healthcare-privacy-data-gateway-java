package com.github.xingjianluo.healthcareprivacydatagatewayjava.service;

import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.DenyReason;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.Grant;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.GrantStatus;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.Purpose;

import java.time.Clock;
import java.util.Optional;

/**
 * 授权判定：按固定优先级给出唯一、可区分的拒绝原因。
 *
 * <p>判定顺序（短路，命中即返回，保证一次请求只对应一种失败原因）：
 * <ol>
 *   <li>{@link DenyReason#UNKNOWN_ACCESSOR} 访问方未登记</li>
 *   <li>{@link DenyReason#NO_GRANT} 患者未对该访问方授权</li>
 *   <li>{@link DenyReason#GRANT_REVOKED} 授权已被患者撤回（撤回优先于过期，
 *       因为撤回是患者主动作出的最新意思表示）</li>
 *   <li>{@link DenyReason#GRANT_EXPIRED} 当前时间已超过授权到期时间</li>
 *   <li>{@link DenyReason#PURPOSE_OUT_OF_SCOPE} 请求用途不在授权用途集合内</li>
 * </ol>
 */
public class AuthorizationPolicyService {

    private final Clock clock;

    public AuthorizationPolicyService(Clock clock) {
        this.clock = clock;
    }

    /** 判定结果：允许或带唯一拒绝原因的拒绝。 */
    public Decision evaluate(Optional<Grant> grant, boolean accessorExists, Purpose purpose) {
        if (!accessorExists) {
            return new Deny(DenyReason.UNKNOWN_ACCESSOR,
                    "访问方未登记，拒绝访问");
        }
        if (grant.isEmpty()) {
            return new Deny(DenyReason.NO_GRANT,
                    "患者未向该访问方出具任何授权");
        }
        Grant g = grant.get();
        if (g.status() == GrantStatus.REVOKED) {
            return new Deny(DenyReason.GRANT_REVOKED,
                    "授权已于 " + g.revokedAt() + " 被患者撤回");
        }
        if (!clock.instant().isBefore(g.expiresAt())) {
            return new Deny(DenyReason.GRANT_EXPIRED,
                    "授权已于 " + g.expiresAt() + " 到期");
        }
        if (!g.allowedPurposes().contains(purpose)) {
            return new Deny(DenyReason.PURPOSE_OUT_OF_SCOPE,
                    "用途 " + purpose + " 不在授权范围 " + g.allowedPurposes() + " 内");
        }
        return new Allow();
    }

    /** 判定结论。 */
    public sealed interface Decision permits Allow, Deny {
    }

    public record Allow() implements Decision {
    }

    public record Deny(DenyReason reason, String message) implements Decision {
    }
}
