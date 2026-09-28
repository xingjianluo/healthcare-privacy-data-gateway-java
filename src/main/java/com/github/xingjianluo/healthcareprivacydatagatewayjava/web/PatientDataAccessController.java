package com.github.xingjianluo.healthcareprivacydatagatewayjava.web;

import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.AccessRequest;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.AccessResult;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.AccessVerdict;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.ChainVerificationResult;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.repository.LocalDataRepository;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.service.AuditChainService;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.service.PatientDataAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 患者数据访问 REST 接口。 */
@RestController
@RequestMapping("/api")
public class PatientDataAccessController {

    private final PatientDataAccessService service;
    private final AuditChainService auditChainService;
    private final LocalDataRepository repository;

    public PatientDataAccessController(PatientDataAccessService service,
                                       AuditChainService auditChainService,
                                       LocalDataRepository repository) {
        this.service = service;
        this.auditChainService = auditChainService;
        this.repository = repository;
    }

    /**
     * 按访问方、患者、用途访问去标识化患者数据。
     * 允许返回 200；拒绝返回 403，body 中带可区分的 denyReason。
     */
    @PostMapping("/patient-data/access")
    public ResponseEntity<AccessResult> access(@RequestBody AccessRequest request) {
        AccessResult result = service.access(request);
        HttpStatus status = result.verdict() == AccessVerdict.ALLOW ? HttpStatus.OK : HttpStatus.FORBIDDEN;
        return ResponseEntity.status(status).body(result);
    }

    /** 校验审计链完整性。 */
    @GetMapping("/audit/verify")
    public ChainVerificationResult verify() {
        return auditChainService.verify();
    }

    /** 撤回指定授权（测试/管理用途），返回是否撤回成功。 */
    @PostMapping("/grants/{grantId}/revoke")
    public Map<String, Object> revoke(@PathVariable String grantId) {
        boolean revoked = repository.revokeGrant(grantId);
        return Map.of("grantId", grantId, "revoked", revoked);
    }
}
