package com.github.xingjianluo.healthcareprivacydatagatewayjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.AccessRequest;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.Purpose;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.repository.LocalDataRepository;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 端到端：REST 状态码、可区分拒绝原因、撤回生效、审计校验端点。 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PatientDataAccessControllerTest {

    private static final Logger log = LoggerFactory.getLogger(PatientDataAccessControllerTest.class);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private LocalDataRepository repository;

    private JsonNode access(String caseName, String accessorId, String patientId, Purpose purpose,
                            int expectedStatus) throws Exception {
        String body = objectMapper.writeValueAsString(new AccessRequest(accessorId, patientId, purpose));
        String response = mockMvc.perform(post("/api/patient-data/access")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(response);
        log.info("[{}] HTTP {} => verdict={}, denyReason={}",
                caseName, expectedStatus, json.path("verdict").asText(),
                json.path("denyReason").asText(null));
        return json;
    }

    @Test
    void httpStatusCodesAndDenyReasonsAreDistinguishable() throws Exception {
        JsonNode allowed = access("HTTP-允许", LocalDataRepository.ORG_A,
                LocalDataRepository.PATIENT_001, Purpose.RESEARCH, 200);
        assertEquals("ALLOW", allowed.path("verdict").asText());
        assertTrue(allowed.path("record").has("pseudonym"));
        assertFalse(allowed.path("record").has("name"));
        assertFalse(allowed.path("record").has("idCard"));

        JsonNode expired = access("HTTP-过期", LocalDataRepository.ORG_B,
                LocalDataRepository.PATIENT_001, Purpose.RESEARCH, 403);
        assertEquals("GRANT_EXPIRED", expired.path("denyReason").asText());

        JsonNode scope = access("HTTP-超范围", LocalDataRepository.ORG_A,
                LocalDataRepository.PATIENT_002, Purpose.RESEARCH, 403);
        assertEquals("PURPOSE_OUT_OF_SCOPE", scope.path("denyReason").asText());

        assertNotEquals(expired.path("denyReason").asText(), scope.path("denyReason").asText());
    }

    @Test
    void revokeEndpointThenAccessIsDeniedAsRevoked() throws Exception {
        JsonNode before = access("HTTP-撤回前", LocalDataRepository.ORG_A,
                LocalDataRepository.PATIENT_002, Purpose.CLINICAL_TRIAL, 200);
        assertEquals("ALLOW", before.path("verdict").asText());

        mockMvc.perform(post("/api/grants/G-003/revoke"))
                .andExpect(status().isOk());
        log.info("[HTTP-撤回] G-003 已通过管理端点撤回");

        JsonNode after = access("HTTP-撤回后", LocalDataRepository.ORG_A,
                LocalDataRepository.PATIENT_002, Purpose.CLINICAL_TRIAL, 403);
        assertEquals("GRANT_REVOKED", after.path("denyReason").asText());
    }

    @Test
    void auditVerifyEndpointReportsValidChain() throws Exception {
        access("HTTP-审计-允许", LocalDataRepository.ORG_A,
                LocalDataRepository.PATIENT_001, Purpose.RESEARCH, 200);
        access("HTTP-审计-拒绝", "ORG-UNKNOWN",
                LocalDataRepository.PATIENT_001, Purpose.RESEARCH, 403);

        String response = mockMvc.perform(get("/api/audit/verify"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(response);
        log.info("[HTTP-审计校验] {}", response);
        assertTrue(json.path("valid").asBoolean(), "审计链应校验通过");
        assertTrue(json.path("actualCount").asInt() >= 2, "成功与失败请求均应留痕");
    }
}
