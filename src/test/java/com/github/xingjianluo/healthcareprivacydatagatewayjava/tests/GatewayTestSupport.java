package com.github.xingjianluo.healthcareprivacydatagatewayjava.tests;

import com.github.xingjianluo.healthcareprivacydatagatewayjava.service.AuditChainService;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.service.ConsentEvaluationService;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.service.DataAccessGateway;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.service.DeidentificationService;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.store.LocalDataStore;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/** 固定基准日 2026-09-28 装配网关，保证判定结论稳定可复现。 */
final class GatewayTestSupport {

    static final Instant FIXED_NOW = Instant.parse("2026-09-28T12:00:00Z");

    private GatewayTestSupport() {
    }

    static TestContext newContext() {
        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        LocalDataStore store = new LocalDataStore();
        ConsentEvaluationService consentService = new ConsentEvaluationService(store, clock);
        DeidentificationService deidService = new DeidentificationService("test-secret", clock);
        AuditChainService auditService = new AuditChainService(clock);
        DataAccessGateway gateway = new DataAccessGateway(
                consentService, deidService, auditService, store);
        return new TestContext(gateway, auditService, deidService, store);
    }

    record TestContext(
            DataAccessGateway gateway,
            AuditChainService audit,
            DeidentificationService deid,
            LocalDataStore store) {
    }
}
