package com.github.xingjianluo.healthcareprivacydatagatewayjava.tests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 端到端 HTTP 接口测试（随机端口启动真实容器）。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccessApiIntegrationTests {

    private static final Logger log = LoggerFactory.getLogger(AccessApiIntegrationTests.class);

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("POST /api/access：允许场景返回假名数据，拒绝场景携带可区分原因")
    void accessEndpointReturnsDistinguishableResults() {
        ResponseEntity<String> allowed = post(
                "{\"patientId\":\"P001\",\"consumerId\":\"C001\",\"purpose\":\"RESEARCH\"}");
        log.info("HTTP 允许场景响应: {}", allowed.getBody());
        assertEquals(HttpStatus.OK, allowed.getStatusCode());
        assertTrue(allowed.getBody().contains("\"allowed\":true"));
        assertTrue(allowed.getBody().contains("PSD-"), "应返回访问方维度假名");

        ResponseEntity<String> revoked = post(
                "{\"patientId\":\"P001\",\"consumerId\":\"C001\",\"purpose\":\"TREATMENT\"}");
        ResponseEntity<String> expired = post(
                "{\"patientId\":\"P002\",\"consumerId\":\"C001\",\"purpose\":\"RESEARCH\"}");
        ResponseEntity<String> scope = post(
                "{\"patientId\":\"P001\",\"consumerId\":\"C002\",\"purpose\":\"RESEARCH\"}");
        log.info("HTTP 拒绝场景: revoked={}, expired={}, scope={}",
                revoked.getBody(), expired.getBody(), scope.getBody());

        assertTrue(revoked.getBody().contains("CONSENT_REVOKED"));
        assertTrue(expired.getBody().contains("CONSENT_EXPIRED"));
        assertTrue(scope.getBody().contains("PURPOSE_OUT_OF_SCOPE"));
        assertFalse(revoked.getBody().contains("CONSENT_EXPIRED"));
    }

    @Test
    @DisplayName("审计接口：可读链、可校验；非法入参返回 400")
    void auditEndpointsAndBadRequest() {
        post("{\"patientId\":\"P001\",\"consumerId\":\"C001\",\"purpose\":\"RESEARCH\"}");

        ResponseEntity<String> audit = restTemplate.getForEntity("/api/audit", String.class);
        ResponseEntity<String> verify = restTemplate.getForEntity("/api/audit/verify", String.class);
        log.info("HTTP 审计链: {}", audit.getBody());
        log.info("HTTP 校验结果: {}", verify.getBody());
        assertEquals(HttpStatus.OK, audit.getStatusCode());
        assertTrue(verify.getBody().contains("\"valid\":true"));

        ResponseEntity<String> bad = post(
                "{\"patientId\":null,\"consumerId\":\"C001\",\"purpose\":\"RESEARCH\"}");
        log.info("HTTP 非法入参状态码: {}", bad.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, bad.getStatusCode());
    }

    private ResponseEntity<String> post(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity("/api/access",
                new HttpEntity<>(json, headers), String.class);
    }
}
