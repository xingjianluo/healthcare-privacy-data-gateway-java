package com.github.xingjianluo.healthcareprivacydatagatewayjava.service;

import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.AccessRequest;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.AccessResult;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.AccessVerdict;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.AuditEntry;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.DenyReason;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.Grant;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.PatientRecord;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.repository.LocalDataRepository;

import java.util.Optional;

/**
 * 患者数据访问门面：组合授权判定、去标识化与审计留痕。
 *
 * <p>关键不变量：<b>每一个进来的请求都恰好写入一条审计记录</b>——
 * 无论访问方未知、无授权、撤回、过期、越权用途，还是允许。审计追加
 * 先于结果构造，因此结果中始终携带 auditEntryId 可供比对。
 */
public class PatientDataAccessService {

    private final LocalDataRepository repository;
    private final AuthorizationPolicyService authorizationPolicyService;
    private final DeidentificationService deidentificationService;
    private final AuditChainService auditChainService;

    public PatientDataAccessService(
            LocalDataRepository repository,
            AuthorizationPolicyService authorizationPolicyService,
            DeidentificationService deidentificationService,
            AuditChainService auditChainService) {
        this.repository = repository;
        this.authorizationPolicyService = authorizationPolicyService;
        this.deidentificationService = deidentificationService;
        this.auditChainService = auditChainService;
    }

    public AccessResult access(AccessRequest request) {
        boolean accessorExists = repository.findAccessor(request.accessorId()).isPresent();
        Optional<Grant> grant = repository.findGrant(request.patientId(), request.accessorId());

        AuthorizationPolicyService.Decision decision =
                authorizationPolicyService.evaluate(grant, accessorExists, request.purpose());

        if (decision instanceof AuthorizationPolicyService.Deny deny) {
            AuditEntry entry = auditChainService.append(request, AccessVerdict.DENY, deny.reason());
            return AccessResult.deny(deny.reason(), deny.message(), entry.entryId());
        }

        // 判定允许：授权存在意味着患者数据存在（种子数据一致性保证）
        PatientRecord patient = repository.findPatient(request.patientId())
                .orElseThrow(() -> new IllegalStateException(
                        "授权存在但患者数据缺失: " + request.patientId()));
        var deidentified = deidentificationService.deidentify(patient, request.accessorId());

        AuditEntry entry = auditChainService.append(request, AccessVerdict.ALLOW, null);
        return AccessResult.allow(deidentified, entry.entryId());
    }
}
