package com.github.xingjianluo.healthcareprivacydatagatewayjava.web;

import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.AccessRequest;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.AccessResult;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.AuditEntry;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.AuditVerificationResult;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.service.AuditChainService;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.service.DataAccessGateway;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 患者数据访问接口。 */
@RestController
@RequestMapping("/api")
public class AccessController {

    private final DataAccessGateway gateway;
    private final AuditChainService auditChainService;

    public AccessController(DataAccessGateway gateway, AuditChainService auditChainService) {
        this.gateway = gateway;
        this.auditChainService = auditChainService;
    }

    @PostMapping("/access")
    public AccessResult access(@RequestBody AccessRequest request) {
        return gateway.access(request);
    }

    @GetMapping("/audit")
    public List<AuditEntry> audit() {
        return auditChainService.entries();
    }

    @GetMapping("/audit/verify")
    public AuditVerificationResult verify() {
        return auditChainService.verify();
    }
}
