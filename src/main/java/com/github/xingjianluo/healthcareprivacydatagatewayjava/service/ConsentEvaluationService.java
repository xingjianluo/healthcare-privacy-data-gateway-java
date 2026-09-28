package com.github.xingjianluo.healthcareprivacydatagatewayjava.service;

import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.AccessDecision;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.AccessPurpose;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.AccessRequest;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.Consent;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.ConsentStatus;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.DenialReason;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.store.LocalDataStore;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * 授权判定。
 *
 * <p>按固定优先级依次检查，不同失败原因返回不同的 {@link DenialReason}，
 * 绝不把多种失败合并成同一种报错：
 * <ol>
 *   <li>访问方不存在 → CONSUMER_NOT_FOUND</li>
 *   <li>患者不存在 → PATIENT_NOT_FOUND</li>
 *   <li>双方之间没有任何授权 → NO_CONSENT</li>
 *   <li>存在授权但均不包含本次用途 → PURPOSE_OUT_OF_SCOPE</li>
 *   <li>匹配用途的授权中已被撤回 → CONSENT_REVOKED</li>
 *   <li>匹配用途的授权不在有效期内 → CONSENT_EXPIRED</li>
 * </ol>
 * 撤回优先于过期（显式撤回是更强的患者意愿表达）。
 */
@Service
public class ConsentEvaluationService {

    private final LocalDataStore dataStore;
    private final Clock clock;

    public ConsentEvaluationService(LocalDataStore dataStore, Clock clock) {
        this.dataStore = dataStore;
        this.clock = clock;
    }

    public AccessDecision evaluate(AccessRequest request) {
        if (request == null || request.patientId() == null || request.consumerId() == null
                || request.purpose() == null) {
            throw new IllegalArgumentException(
                    "access request must contain patientId, consumerId and purpose");
        }

        if (dataStore.findConsumer(request.consumerId()).isEmpty()) {
            return deny(DenialReason.CONSUMER_NOT_FOUND);
        }
        if (dataStore.findPatient(request.patientId()).isEmpty()) {
            return deny(DenialReason.PATIENT_NOT_FOUND);
        }

        List<Consent> all = dataStore.findConsents(request.patientId(), request.consumerId());
        if (all.isEmpty()) {
            return deny(DenialReason.NO_CONSENT);
        }

        AccessPurpose purpose = request.purpose();
        List<Consent> matching = all.stream()
                .filter(c -> c.allowedPurposes().contains(purpose))
                .toList();
        if (matching.isEmpty()) {
            return deny(DenialReason.PURPOSE_OUT_OF_SCOPE);
        }

        Optional<Consent> valid = matching.stream()
                .filter(c -> c.status() == ConsentStatus.ACTIVE && isWithinValidity(c))
                .findAny();
        if (valid.isPresent()) {
            return new AccessDecision(true, null, valid.get());
        }

        if (matching.stream().anyMatch(c -> c.status() == ConsentStatus.REVOKED)) {
            return deny(DenialReason.CONSENT_REVOKED);
        }
        return deny(DenialReason.CONSENT_EXPIRED);
    }

    private boolean isWithinValidity(Consent consent) {
        var now = clock.instant();
        return !now.isBefore(consent.validFrom()) && now.isBefore(consent.validUntil());
    }

    private AccessDecision deny(DenialReason reason) {
        return new AccessDecision(false, reason, null);
    }
}
