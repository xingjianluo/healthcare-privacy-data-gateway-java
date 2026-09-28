package com.github.xingjianluo.healthcareprivacydatagatewayjava.service;

import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.AccessDecision;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.AccessRequest;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.AccessResult;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.DeidentifiedPatient;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.DenialReason;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.PatientRecord;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.store.LocalDataStore;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 访问编排。
 *
 * <p>无论判定通过与否，都会先追加审计记录再返回结果，保证判定失败的请求也留痕；
 * 允许时先去标识化再返回最小字段，原始记录绝不外泄。
 */
@Service
public class DataAccessGateway {

    private final ConsentEvaluationService consentEvaluationService;
    private final DeidentificationService deidentificationService;
    private final AuditChainService auditChainService;
    private final LocalDataStore dataStore;

    public DataAccessGateway(ConsentEvaluationService consentEvaluationService,
                             DeidentificationService deidentificationService,
                             AuditChainService auditChainService,
                             LocalDataStore dataStore) {
        this.consentEvaluationService = consentEvaluationService;
        this.deidentificationService = deidentificationService;
        this.auditChainService = auditChainService;
        this.dataStore = dataStore;
    }

    public AccessResult access(AccessRequest request) {
        AccessDecision decision = consentEvaluationService.evaluate(request);
        String requestId = UUID.randomUUID().toString();

        if (!decision.allowed()) {
            DenialReason reason = decision.denialReason();
            auditChainService.append(requestId, request, false, reason);
            return new AccessResult(false, reason, messageFor(reason), requestId, null);
        }

        PatientRecord patient = dataStore.findPatient(request.patientId()).orElseThrow();
        DeidentifiedPatient payload =
                deidentificationService.deidentify(patient, request.consumerId());
        auditChainService.append(requestId, request, true, null);
        return new AccessResult(true, null, "ACCESS_ALLOWED", requestId, payload);
    }

    static String messageFor(DenialReason reason) {
        return switch (reason) {
            case CONSUMER_NOT_FOUND -> "DENIED: access party is not registered (CONSUMER_NOT_FOUND)";
            case PATIENT_NOT_FOUND -> "DENIED: patient record does not exist (PATIENT_NOT_FOUND)";
            case NO_CONSENT -> "DENIED: no consent exists between patient and access party (NO_CONSENT)";
            case CONSENT_REVOKED -> "DENIED: consent for the requested purpose has been revoked (CONSENT_REVOKED)";
            case CONSENT_EXPIRED -> "DENIED: consent for the requested purpose has expired (CONSENT_EXPIRED)";
            case PURPOSE_OUT_OF_SCOPE -> "DENIED: requested purpose is outside the consented scope (PURPOSE_OUT_OF_SCOPE)";
        };
    }
}
