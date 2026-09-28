# 跨机构科研共享患者数据访问网关（本地模拟）

一套面向跨机构科研共享场景的患者数据访问接口，使用本地内存数据模拟
患者记录、患者授权和访问机构。核心能力：

1. **授权判定**：按患者授权范围与使用用途判定，不同失败原因返回可区分的拒绝码；
2. **去标识化**：最小字段白名单 + 自由文本敏感片段掩码，同一授权体系内假名稳定可复现，
   不同访问方之间无法通过返回内容关联同一患者；
3. **可校验审计链**：每次访问（含判定失败）都追加一条哈希链记录，
   并发下不重号、不丢失，任何篡改都能在校验时被定位。

技术栈：Java 21、Spring Boot 3.4、JUnit 5，无外部数据库依赖。

---

## 1. 授权判定规则

判定在 `AuthorizationPolicyService` 中实现，按**固定优先级短路执行**，
一次请求只返回一种失败原因，绝不把多种失败混成同一种报错：

| 顺序 | 条件 | 拒绝码 `DenyReason` |
|----|------|--------------------|
| 1 | 访问方 ID 未在系统登记 | `UNKNOWN_ACCESSOR` |
| 2 | 患者未向该访问方出具任何授权 | `NO_GRANT` |
| 3 | 授权状态为已撤回（`REVOKED`） | `GRANT_REVOKED` |
| 4 | 当前时间 ≥ 授权到期时间 | `GRANT_EXPIRED` |
| 5 | 请求用途不在授权用途集合内 | `PURPOSE_OUT_OF_SCOPE` |
| — | 以上全部通过 | 允许（`ALLOW`） |

说明：

- **撤回优先于过期**：撤回是患者主动作出的最新意思表示，即使授权同时已过期，
  也报 `GRANT_REVOKED`，保证拒绝原因稳定、可解释。
- 判定使用注入的 `Clock`，测试固定在 `2026-09-28T12:00:00Z`，结论不受运行日期影响。
- 支持的用途：`RESEARCH`（科研）、`CLINICAL_TRIAL`（临床试验）、`MARKETING`（营销）。

### 本地种子数据（基准日 2026-09-28）

| 授权 | 患者 | 访问方 | 允许用途 | 状态/有效期 |
|------|------|--------|---------|------------|
| G-001 | P-001 张三 | ORG-A 大学基因组研究中心 | RESEARCH, CLINICAL_TRIAL | 有效至 2027-12-31 |
| G-002 | P-001 张三 | ORG-B 市立医院科研处 | RESEARCH | **2025-01-01 已过期** |
| G-003 | P-002 李四 | ORG-A | CLINICAL_TRIAL | 有效（科研用途即超范围） |
| G-004 | P-002 李四 | ORG-B | RESEARCH | **2026-09-01 已撤回** |
| — | P-001 | ORG-X 某商业分析公司 | — | 访问方已登记但**无任何授权** |

---

## 2. 去标识化口径

判定允许后，`DeidentificationService` 按以下口径处理返回内容：

### 2.1 字段白名单（最小返回）

返回记录 `DeidentifiedRecord` 只包含 5 个字段：

- `pseudonym`：稳定假名；
- `gender`：性别（科研结构变量，保留）；
- `diagnoses`：ICD 诊断编码列表（保留，原样拷贝，不做文本处理，避免误删）；
- `labResults`：检验键值对（保留，如 HbA1c、LDL、SBP）；
- `clinicalNoteMasked`：掩码后的自由文本。

`patientId / name / idCard / phone / birthDate` 等直接标识符根本不会被拷贝进结果对象。

### 2.2 假名规则

```
pseudonym = "PSN-" + SHA256(systemPepper | accessorId | patientId) 的前 32 位十六进制
```

- **同访问方 + 同患者**：输入完全一致，假名稳定、可复现（多次访问/不同用途都一样）；
- **不同访问方**：`accessorId` 参与哈希，假名互不相同，无法跨机构关联；
- 单向哈希，结果中不暴露真实患者 ID。

### 2.3 自由文本敏感片段掩码

按顺序替换（先身份证后手机号，避免长数字串被短规则部分误匹配）：

| 内容 | 掩码 |
|------|------|
| 患者姓名 | `[NAME]` |
| 18 位身份证号 | `[ID_CARD]` |
| 11 位手机号（1 开头） | `[PHONE]` |
| 出生日期 / 日期 | `[BIRTH_DATE]` / `[DATE]` |
| 地址片段（"家住…/住址…/地址…" 引导） | `家住[ADDRESS]` |

诊断、用药、医嘱等临床含义文本保留。示例：

> 患者[NAME]（电话[PHONE]，身份证[ID_CARD]）主诉口干多尿三月余，既往2型糖尿病，
> 医嘱二甲双胍随餐服用，家住[ADDRESS]。

---

## 3. 审计链及校验方式

`AuditChainService` 实现线程安全的追加式哈希链（内存 `ArrayList` + 单锁串行化追加）。

### 3.1 链式结构

每条 `AuditEntry` 含：`sequence`（从 0 递增）、`entryId`（UUID）、`timestamp`、
请求三要素（accessorId / patientId / purpose）、判定结果（verdict / denyReason）、
`prevHash`、`currentHash`。

```
currentHash = SHA256(
  prevHash | sequence | entryId | timestamp
  | accessorId | patientId | purpose | verdict | denyReason)
```

- 第 0 条的 `prevHash = "GENESIS"`，其余等于上一条的 `currentHash`；
- 追加在同一把锁内完成"取尾哈希 → 分配序号 → 建条目 → 入链"，
  **并发访问不会重号、不会丢条目，entryId 全局唯一**；
- 判定失败的请求同样入链，verdict=`DENY` 并记录具体 denyReason。

### 3.2 校验

`GET /api/audit/verify`（或 `AuditChainService.verify()`）从头重算：

1. `sequence` 必须等于其在链中的位置（连续、无重号）；
2. 每条 `prevHash` 必须等于上一条的 `currentHash`；
3. 每条 `currentHash` 必须等于按字段重算的哈希值。

任一不符即返回 `valid=false`，并在 `brokenAtSequences` 中给出全部断裂序号：

- **只改业务字段**（如把 DENY 改成 ALLOW）：该条重算哈希不匹配，定位到该条；
- **连哈希一起伪造**：该条自身及下一条的 `prevHash` 衔接同时断裂，级联暴露；
- 删除/插入条目会导致序号连续性与哈希衔接同时断裂。

---

## 4. HTTP 接口

基准地址：`http://localhost:8080`

### 访问患者数据

```
POST /api/patient-data/access
Content-Type: application/json

{ "accessorId": "ORG-A", "patientId": "P-001", "purpose": "RESEARCH" }
```

- 允许：`200`，body 含 `verdict=ALLOW` 与去标识化 `record`；
- 拒绝：`403`，body 含 `verdict=DENY`、可区分的 `denyReason` 与可读 `message`；
- 无论成功失败，body 都带 `auditEntryId`，可与审计链逐条核对。

### 校验审计链

```
GET /api/audit/verify
=> { "valid": true, "expectedCount": 3, "actualCount": 3,
     "brokenAtSequences": [], "detail": "审计链完整，..." }
```

### 撤回授权（管理/测试用途）

```
POST /api/grants/{grantId}/revoke
=> { "grantId": "G-001", "revoked": true }
```

撤回立即生效，之后对该授权的访问返回 `403 / GRANT_REVOKED`。

---

## 5. 本地验证步骤

前置：JDK 17+（编译目标 21，本机以 JDK 21/25 验证均可）、Maven 3.6+。

```bash
# 1. 运行全部测试（18 个：单元 + Web 集成）
mvn test

# 只跑核心场景单元测试
mvn test -Dtest=PatientDataAccessTest

# 2. 启动应用（application.properties 中 server.port=0，
#    如需固定端口可改为 8080）
mvn spring-boot:run

# 3. 手工验证（另开终端）
curl -s -X POST http://localhost:8080/api/patient-data/access \
  -H 'Content-Type: application/json' \
  -d '{"accessorId":"ORG-A","patientId":"P-001","purpose":"RESEARCH"}'

curl -s -X POST http://localhost:8080/api/patient-data/access \
  -H 'Content-Type: application/json' \
  -d '{"accessorId":"ORG-B","patientId":"P-001","purpose":"RESEARCH"}'   # GRANT_EXPIRED

curl -s http://localhost:8080/api/audit/verify
```

### 测试覆盖的场景

`PatientDataAccessTest`（固定时钟，不启动容器）：

- 有效授权允许、五类拒绝原因各自命中；
- `denyReasonsAreDistinct`：五类失败原因两两不同，不允许混成一种报错；
- `revokeTakesEffectImmediatelyAndIsDistinct`：授权撤回前后结论对比，
  撤回后稳定报 `GRANT_REVOKED`，重复撤回不生效；
- `pseudonymStableWithinAccessorAndDifferentAcrossAccessors`：假名同方稳定、跨方不同；
- `deidentificationRemovesDirectIdentifiersAndMasksFreeText`：直接标识符不出现于返回内容、
  自由文本姓名/身份证/电话/地址均被掩码，诊断与检验结果完整保留；
- `deniedRequestsAlsoLeaveAuditTrailAndChainVerifies`：失败请求同样留痕；
- `concurrentAccessProducesCompleteOrderedUniqueChain`：8 线程 × 25 次并发
  （含成功与两类失败），200 条记录无丢失、无重号、ID 唯一、哈希衔接、校验通过；
- `tamperedChainIsDetected` / `tamperedHashFieldIsDetectedAndCascades`：
  篡改业务字段或伪造哈希均被发现并定位断裂序号。

`PatientDataAccessControllerTest`（启动 Spring 容器 + MockMvc）：

- HTTP 200/403 状态码与拒绝码可区分、返回 JSON 不含 name/idCard 等字段；
- 撤回端点调用后访问变为 `GRANT_REVOKED`；
- `/api/audit/verify` 端点报告链完整。

测试日志通过 SLF4J 打印每个用例的**关键输入（accessorId / patientId / purpose）
与判定结果（verdict / denyReason / 审计ID）**，以及去标识化文本、并发统计和篡改校验详情。

---

## 6. 代码结构

```
model/            枚举（Purpose/DenyReason/GrantStatus/AccessVerdict）与记录模型
repository/       LocalDataRepository：内存患者、访问方、授权种子数据
service/          AuthorizationPolicyService  授权判定（优先级短路）
                  PseudonymService           访问方隔离的稳定假名
                  DeidentificationService    字段白名单 + 文本掩码
                  AuditChainService          并发安全哈希链与校验
                  PatientDataAccessService   门面：判定→去标识化→审计留痕
config/           Spring 装配（Clock 可替换，便于固定时间测试）
web/              PatientDataAccessController：REST 接口
```
