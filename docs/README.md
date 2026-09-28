# 跨机构科研共享患者数据访问接口（本地模拟）

面向跨机构科研共享场景的患者数据访问网关：按患者授权范围与使用用途做访问判定，
对放行结果做去标识化与最小字段返回，并为每次访问（含被拒绝的访问）形成可校验的
哈希审计链。所有患者、授权、访问方数据均为本地内存种子数据，判定结论稳定、可复现。

## 1. 接口一览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/access` | 发起一次患者数据访问 |
| GET | `/api/audit` | 查看当前审计链全部记录 |
| GET | `/api/audit/verify` | 校验审计链完整性 |

访问请求体：

```json
{ "patientId": "P001", "consumerId": "C001", "purpose": "RESEARCH" }
```

允许响应（数据已去标识化、字段最小化）：

```json
{
  "allowed": true,
  "denialReason": null,
  "message": "ACCESS_ALLOWED",
  "requestId": "3e43826f-c353-4055-b25e-4b09e96d4019",
  "data": {
    "pseudonym": "PSD-655d5555f8246236",
    "gender": "M",
    "ageBand": "40-49",
    "diagnosisCodes": ["I21.4", "E11.9"],
    "labResults": { "glucose_fasting": 7.2, "ldl_cholesterol": 3.4, "systolic_bp": 142.0 },
    "scrubbedNotes": "患者[脱敏]（联系电话[脱敏]）自述胸痛2小时。既往糖尿病史，随访提示[脱敏]抗体待复查，否认近期手术史。"
  }
}
```

拒绝响应（HTTP 仍为 200，结论在响应体；非法入参为 400）：

```json
{
  "allowed": false,
  "denialReason": "CONSENT_REVOKED",
  "message": "DENIED: consent for the requested purpose has been revoked (CONSENT_REVOKED)",
  "requestId": "7b68acd2-bd61-4657-9de3-a4251835592d",
  "data": null
}
```

## 2. 授权判定规则

判定输入为 `(patientId, consumerId, purpose)`，由 `ConsentEvaluationService`
按以下**固定优先级**逐条检查。不同失败原因返回不同的 `denialReason`，
不会把多种失败混成一种报错：

| 优先级 | 条件 | 拒绝原因码 |
| --- | --- | --- |
| 1 | 访问方未注册 | `CONSUMER_NOT_FOUND` |
| 2 | 患者不存在 | `PATIENT_NOT_FOUND` |
| 3 | 患者与访问方之间没有任何授权 | `NO_CONSENT` |
| 4 | 存在授权但都不包含本次用途 | `PURPOSE_OUT_OF_SCOPE` |
| 5 | 匹配用途的授权处于撤回状态（撤回优先于过期） | `CONSENT_REVOKED` |
| 6 | 匹配用途的授权不在有效期内 | `CONSENT_EXPIRED` |
| - | 存在状态 ACTIVE、在 `[validFrom, validUntil)` 内且用途匹配的授权 | 允许 |

- 用途是枚举：`RESEARCH`、`TREATMENT`、`MARKETING`、`PUBLIC_HEALTH`，
  授权按用途集合逐一匹配，超范围用途直接拒绝。
- 有效期判定使用可注入的 `Clock`，测试固定在基准日 **2026-09-28**，
  因此“有效/过期”结论可复现。
- 撤回是显式状态 `REVOKED`，与“自然过期”严格区分；
  同一用途既有撤回又有过期时优先报撤回（更强的患者意愿表达）。

### 本地种子数据（基准日 2026-09-28）

| 授权 | 患者→访问方 | 用途 | 状态/有效期 | 用于演示 |
| --- | --- | --- | --- | --- |
| CS001 | P001→C001 | RESEARCH | ACTIVE 2026 全年 | 正常放行 |
| CS002 | P001→C001 | TREATMENT | REVOKED | 授权撤回 |
| CS003 | P002→C001 | RESEARCH | 2025-01-01 ~ 2026-08-31 | 授权过期 |
| CS004 | P001→C002 | TREATMENT | ACTIVE 2026 全年 | RESEARCH 访问即越权用途 |
| CS005 | P002→C002 | RESEARCH | ACTIVE 2026 全年 | 假名跨方不可关联对照 |
| - | C003 | 无任何授权 | - | 未授权访问方（NO_CONSENT） |

## 3. 去标识化口径

由 `DeidentificationService` 实现，**只在授权放行后执行**，原始记录永不外泄：

1. **稳定假名（同一授权体系内可复现）**

   ```
   pseudonym = "PSD-" + HMAC-SHA256(secret, consumerId + ":" + patientId) 的前 8 字节十六进制
   ```

   - 同一访问方对同一患者多次访问 → 假名完全相同，可用于纵向科研关联；
   - 不同访问方对同一患者 → 假名不同（HMAC 以 consumerId 为前缀），
     无法通过返回内容跨机构关联同一个自然人；
   - HMAC 为带密钥单向函数，假名不能反推出 patientId。
   - 密钥通过 `pseudonymSecret` Bean 注入（开发默认 `local-dev-pseudonym-secret`，
     测试固定为 `test-secret`），生产环境应通过配置/环境变量覆盖。

2. **直接标识符一律不返回**：姓名、身份证号、手机号在返回结构中直接消失；
   出生年份泛化为 10 岁年龄段（如 1985 年生 → `40-49`），性别保留。

3. **自由文本敏感片段脱敏**（`notes` → `scrubbedNotes`，替换为 `[脱敏]`）：
   - 18 位身份证号（正则 `\d{17}[0-9Xx]`）；
   - 中国大陆手机号（`1[3-9]\d{9}`，带边界保护避免误伤长数字串）；
   - 患者本人姓名；
   - 敏感疾病/行为关键词：`HIV / 艾滋病 / AIDS`、`精神分裂`、`抑郁症`、
     `梅毒`、`淋病`、`吸毒`、`药物滥用`、`酒精依赖`。

4. **科研所需结构化字段原样保留，不做误删**：ICD 诊断编码（`diagnosisCodes`）
   与检验结果（`labResults`）是结构化科研字段，不受文本关键词脱敏影响
   （例如编码 `E11.9` 不会因为关键词而被删除）。

## 4. 审计链与校验方式

由 `AuditChainService` 实现内存哈希链（可作为持久化实现的参照）：

- **记录内容**：序号、UTC 时间戳、requestId、患者/访问方/用途、判定结论
  （`ALLOW`/`DENY`）、拒绝原因、前驱哈希、本条哈希。
- **哈希结构**：

  ```
  entryHash_n = SHA-256(
      sequence | timestamp | requestId | patientId | consumerId | purpose
      | decision | denialReason | previousHash_n-1
  )
  previousHash_1 = 64 个 0（创世哈希）
  ```

- **并发安全（不重、不丢）**：追加过程在同一把锁内完成
  “取尾哈希 → 分配序号 → 入链”，序号由 `AtomicLong` 生成、严格连续；
  允许与拒绝请求都走同一追加路径。并发测试以 50 线程 × 20 次 = 1000 次
  混合判定验证：记录数恰好 1000、序号无重复无缺失、requestId 无重复、整链有效。
- **完整性校验**（`GET /api/audit/verify`，或直接调用 `verify()`）按顺序检查：
  1. 序号是否从 1 开始连续（缺号/重号即失败）；
  2. 每条 `previousHash` 是否等于上一条 `entryHash`（链接断裂）；
  3. 用记录字段重算 SHA-256，是否与 `entryHash` 一致（内容被篡改）。

  返回 `{valid, brokenAtSequence, message}`，校验失败时 `brokenAtSequence`
  明确给出**首个断裂位置**，例如：
  `valid=false, brokenAtSequence=2, message="entry hash mismatch at sequence 2 (record tampered)"`。
  任一条目的任何字段（包括时间戳、判定结论、拒绝原因）被改动都会被发现，
  且其后所有记录的链接同时失配。

## 5. 本地验证步骤

前置：JDK 21+、Maven 3.6+。

```bash
# 1. 编译
mvn compile

# 2. 运行全部测试（14 个：判定场景、去标识化、审计链篡改、1000 次并发、HTTP 集成）
mvn test

# 3. 只跑并发/审计相关测试
mvn -Dtest=ConcurrentAccessTests,AuditChainTests test

# 4. 启动服务（端口见日志，或自行在 application.properties 固定）
mvn spring-boot:run
```

服务启动后用 curl 验证：

```bash
# 正常放行（RESEARCH 在授权范围内）
curl -s -X POST localhost:8080/api/access -H 'Content-Type: application/json' \
  -d '{"patientId":"P001","consumerId":"C001","purpose":"RESEARCH"}'

# 授权已撤回 -> CONSENT_REVOKED
curl -s -X POST localhost:8080/api/access -H 'Content-Type: application/json' \
  -d '{"patientId":"P001","consumerId":"C001","purpose":"TREATMENT"}'

# 授权已过期 -> CONSENT_EXPIRED
curl -s -X POST localhost:8080/api/access -H 'Content-Type: application/json' \
  -d '{"patientId":"P002","consumerId":"C001","purpose":"RESEARCH"}'

# 越权用途 -> PURPOSE_OUT_OF_SCOPE
curl -s -X POST localhost:8080/api/access -H 'Content-Type: application/json' \
  -d '{"patientId":"P001","consumerId":"C002","purpose":"RESEARCH"}'

# 未授权访问方 -> NO_CONSENT
curl -s -X POST localhost:8080/api/access -H 'Content-Type: application/json' \
  -d '{"patientId":"P001","consumerId":"C003","purpose":"RESEARCH"}'

# 查看审计链 / 校验完整性
curl -s localhost:8080/api/audit | jq .
curl -s localhost:8080/api/audit/verify | jq .
```

测试日志会打印每次访问的关键输入（患者/访问方/用途）与判定结果
（allowed、拒绝原因、requestId）、并发统计与审计链校验结论，便于人工复核。

## 6. 代码结构

```
src/main/java/com/github/xingjianluo/healthcareprivacydatagatewayjava
├── model/        枚举与领域记录（授权、请求、判定、去标识化结果、审计条目等）
├── store/        LocalDataStore：本地模拟的患者/访问方/授权种子数据
├── service/      ConsentEvaluationService 授权判定
│                 DeidentificationService 假名与脱敏
│                 AuditChainService 哈希审计链
│                 DataAccessGateway 判定→去标识化→留痕 编排
├── web/          AccessController / ApiExceptionHandler
└── config/       Clock、LocalDataStore、假名密钥装配
src/test/...      GatewayScenarioTests / AuditChainTests
                  ConcurrentAccessTests / AccessApiIntegrationTests
```
