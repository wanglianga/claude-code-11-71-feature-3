# 省级医保费用查询改写验证服务

面向稽核、病种付费（DRG）和预算团队的费用明细查询改写验证与月度费用排行平台。技术栈：**Java 25 + Spring Boot 3.5 + PostgreSQL 18**（JDBC/JdbcTemplate + Flyway + springdoc-openapi）。

平台包含两个相互独立又共用快照/账号体系的模块：

1. **查询改写验证**：月结、政策切换和审计期，多人同时按机构、诊断、药品与结算状态筛选数亿行数据。对慢查询做改写前验证——在冻结快照的只读副本上以受限资源影子执行原语句与候选语句，由裁判器逐位比较明细主键集合、金额与机构级分组，通过后才允许经发布门禁绑定到参数范围与权限策略上线。
2. **月度费用排行（本题新增）**：审计人员按医院、科室和药品导出费用排名。提供指标口径版本库、排行榜构建器、查询路由决策器、影子排行比较器和失效登记册，在“查询时扫描明细”与“与口径和快照绑定的预计算结果”两条路径之间按请求频率、可接受等待、口径变更影响面与授权过滤粒度动态路由，而不是把某一种路径固定给所有月份。

## 模块与实现对照

| 需求模块 | 实现 | 说明 |
| --- | --- | --- |
| 查询登记 | `POST /api/registrations` | 保存语句摘要(SHA-256)、用途、授权范围、参数形态、关联快照 |
| 计划采集器 | `POST /api/registrations/{id}/plans` | EXPLAIN(FORMAT JSON) 采集执行计划、估计扫描量、锁等待(pg_stat_activity 采样)、代价；按**参数签名**分别采集 |
| 规则诊断器 | `POST /api/registrations/{id}/diagnose` | 识别可索引化条件(to_char 包装)、隐式类型转换、冗余关联、跨期过滤、全表扫描；按 (登记, 参数签名, 计划哈希) **分别建档** |
| 候选生成器 | `POST /api/registrations/{id}/candidates` | 输出等价的条件下推、分页、预聚合、连接重排（含冗余连接消除） |
| 影子执行器 | `POST /api/candidates/{id}/shadow` | 冻结快照 + 只读事务 + 受限资源(statement_timeout/lock_timeout/work_mem/并行度)运行原语句与候选语句，保存主键集合、总金额、机构分组、实际计划 |
| 结果裁判器 | `POST /api/candidates/{id}/judge` | 比较明细主键集合、金额、机构级分组，定位缺失原因；**总金额相同而机构分组不同 → 阻断并列出差异组** |
| 发布门禁 | `POST /api/candidates/{id}/gate` | 仅 PASS 且 CURRENT 的裁判可进门禁；参数范围**不得超出已验证范围**（防止小范围验证推广到全省） |
| 路由器 | `POST /api/router/query` | 按门禁状态/裁判时效/策略版本/参数范围路由到候选或原语句 |
| 回退账本 | `GET /api/ledger` | 记录 ROLLBACK / POLICY_NARROW / DATA_REFRESH / POLICY_REBIND 的路由切换、受影响请求与恢复依据 |

### 月度费用排行模块（新增）

| 需求模块 | 实现 | 说明 |
| --- | --- | --- |
| 指标口径版本库 | `/api/metric-calibers` | 分类、剔除、排序、并列四类规则整体版本化（DRAFT/ACTIVE/RETIRED），任意两版本可做四要素差异（CATEGORY/EXCLUSION/SORT/TIE） |
| 排行榜构建器 | `/api/ranking-builds` | 一次构建绑定口径+冻结快照，记录输入快照、构建进度（PENDING/RUNNING/READY/PARTIAL_READY/FAILED/INVALIDATED）与可用范围；产物为 月份×维度×机构范围 分区，`holdMonths` 模拟构建延迟、`/advance` 推进 |
| 查询路由决策器 | `/api/ranking-router/query` | 按口径版本、月份、授权、时效目标、请求频率选择 PRECOMPUTED / ON_DEMAND_SCAN / WAITING / FALLBACK_VERIFIED；每次决策落库并附逐条理由 |
| 影子排行比较器 | `/api/ranking-shadow/compare` | 同快照比较两口径的前 N 名集合/顺序、金额、并列名次与授权后可见机构；并列规则不同强制转发布审批 |
| 发布审批 | `/api/ranking-shadow/approvals` | 承接并列规则差异等阻断项，PENDING/APPROVED/REJECTED 留痕 |
| 失效登记册 | `/api/caliber-invalidations` | 政策修订精确计算传播到的月份、药品分类、药品编码与排行分区；仅失效受影响分区，生效月之前的封账月报保留旧口径；支持按新口径重建 |

排行口径四要素（存于 `metric_caliber_version`）：

- **分类规则** `categoryRules`：`[{from,to,drugCodes}]`，`drugCodes` 为空表示整类映射，非空表示仅调整个别药品（局部分类调整）；
- **剔除规则** `exclusionRules`：`{settleStatuses,drugCategories,drugCodes}`，分类剔除作用于分类映射之后的有效分类；
- **排序规则** `sortRules`：`{metric:AMOUNT/COUNT, direction:DESC/ASC}`；
- **并列规则** `tieRules`：`COMPETITION` 竞赛并列（1,1,3）/ `DENSE` 稠密并列（1,1,2）/ `SEQUENTIAL` 不并列（1,2,3），同额按编码升序稳定排序。

两条执行路径（不固定给所有月份，由路由器按请求特征选择）：

- **查询时扫描 ON_DEMAND_SCAN**：在冻结快照上按当前口径实时聚合，无需等待构建、能立即响应新增临时筛选（`adhocFilters`），代价是深分页持续占用计算资源；
- **预计算 PRECOMPUTED**：结果分区与口径版本、数据快照双重绑定并保存多版，高频审计请求限制在已验证结果中，深分页不再扫描明细；口径变更后由失效登记册精确失效并重建。

## 验证方式的关键决策

- **选择冻结快照的只读副本**（而非隔离可回放分析副本）：`POST /api/snapshots/freeze` 把业务基表复制为 `snap_*` 物理表（含索引与 ANALYZE 统计），结构、索引、统计信息都贴近正式统计库；影子执行只打快照，不打生产基表。
- **代价**：验证结论只代表该快照。数据更新后再次 freeze 时，旧裁判自动标记 `STALE`、依赖旧结论的灰度/已发布门禁自动 `BLOCKED` 并记 `DATA_REFRESH` 账本，路由回退原语句，需按风险重验。
- **参数倾斜**：相同 SQL 在不同参数范围下产生不同计划哈希时，诊断器分别建档（演示场景 1 可复现）。
- **权限收窄**：灰度期间新策略生效，旧策略门禁的在途请求标记 `PENDING_REVIEW`（复核前不再返回），新请求以 `POLICY_RETIRED` 拒绝，处置写入账本。

## 快速开始

### 方式一：Docker Compose（一键）

```bash
docker compose up --build
# 应用: http://localhost:8080   Swagger UI: http://localhost:8080/swagger-ui.html
```

compose 含 PostgreSQL 18（ccdev/ccdev）与应用两个服务，应用等数据库健康检查后启动，Flyway 自动建表与种子数据。

### 方式二：本地运行

前置：PostgreSQL 可连接（默认 `127.0.0.1:5432`，库/用户/密码均为 `ccdev`，可用环境变量 `DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASS` 覆盖）。

```bash
# 开发模式
mvn spring-boot:run

# 或打包后运行
mvn package -DskipTests
java -jar target/fee-rewrite-verify-1.0.0.jar
```

首次启动由 Flyway 执行 `V1__schema.sql`（改写验证元数据模型）、`V2__seed.sql`（确定性费用样例：24000 行明细、12 家机构、30 病种、50 药品、正确/缺陷两版预聚合表、测试账号、权限策略 v1）与 `V3__ranking_metrics.sql`（排行口径/构建/分区/路由决策/影子比较/发布审批/失效登记七张表，含默认排行口径 v1 与排行权限策略）。

### 测试

```bash
mvn test
```

含改写引擎与参数分级单元测试，以及在独立 schema（`verify_it`）中真实跑通全部场景的集成测试（需本地 PostgreSQL）。

## 测试账号

路由器通过 `token` 识别用户（演示用静态令牌，存于 `app_user` 表）：

| 账号 | token | 角色 | 机构 | 说明 |
| --- | --- | --- | --- | --- |
| audit01 | `tok-audit01` | AUDIT | ORG001 | 稽核员（机构级） |
| audit02 | `tok-audit02` | AUDIT | ORG002 | 稽核员（机构级） |
| drg01 | `tok-drg01` | DRG | 省级 | 病种付费专员 |
| budget01 | `tok-budget01` | BUDGET | 省级 | 预算分析员 |

权限策略按版本管理（`fee-query` 与 `fee-ranking` 两族）：v1 允许 AUDIT 查 ORG001~003、DRG/BUDGET 全省；演示中创建的 v2 收窄为 AUDIT 仅 ORG001、DRG 仅 ORG001/002。

## 验证方式（本题）

### 1. 一键演示（推荐）

```bash
curl -X POST http://localhost:8080/api/demo/run | jq
```

一次调用真实落库跑通六个场景并返回证据：

| 场景 | 关键证据字段 | 预期 |
| --- | --- | --- |
| ① 参数倾斜 | `scenario1_paramSkew` | 窄参数(单月+小机构)→Bitmap Index Scan，宽参数(4个月+全机构)→Seq Scan；`plansDiffer=true`，`diagnosisFiledSeparately=true`，分别建档 2 份 |
| ② 分组不一致 | `scenario2_groupMismatchBlocked` | stale 预聚合候选 `verdict=BLOCK`、`totalAmountEqual=true`、`orgGroupEqual=false`，`orgGroupDiff` 定位 ORG001(+82137.78)/ORG002(-82137.78) |
| ③ 发布门禁 | `scenario3_releaseGate` | BLOCK 候选进门禁被拒、超验证范围绑定被拒；范围内路由 CANDIDATE、范围外回退 ORIGINAL |
| ④ 权限收窄 | `scenario4_policyNarrow` | 在途请求 → `PENDING_REVIEW`；旧策略新请求 → `REJECTED(POLICY_RETIRED)`；账本记 `POLICY_NARROW` |
| ⑤ 回退演练 | `scenario5_rollback` | 门禁 `ROLLED_BACK`、`affectedRequests≥2`、后续路由回退 ORIGINAL、账本记 `ROLLBACK` |
| ⑥ 数据刷新重验 | `scenario6_dataRefresh` | 旧裁判 `STALE`、门禁 `BLOCKED`、路由回退 ORIGINAL、账本记 `DATA_REFRESH` |

### 2. 手工分步验证（curl 示例）

```bash
# 冻结快照 → 登记 → 采计划（窄/宽两组参数）→ 诊断 → 生成候选 → 影子执行 → 裁判
curl -X POST localhost:8080/api/snapshots/freeze -H 'Content-Type: application/json' -d '{"name":"manual"}'
curl -X POST localhost:8080/api/registrations -H 'Content-Type: application/json' -d @doc/registration.json
curl -X POST localhost:8080/api/registrations/1/plans -H 'Content-Type: application/json' \
     -d '{"params":{"monthFrom":"2025-08","monthTo":"2025-08","orgCode":"ORG003"}}'
curl -X POST localhost:8080/api/registrations/1/plans -H 'Content-Type: application/json' \
     -d '{"params":{"monthFrom":"2025-11","monthTo":"2026-02"}}'
curl -X POST localhost:8080/api/registrations/1/diagnose
curl -X POST localhost:8080/api/registrations/1/candidates -H 'Content-Type: application/json' \
     -d '{"types":["PUSHDOWN","PAGINATION","JOIN_REORDER","PRE_AGGREGATE"],"preAggregateVariants":["current","stale"]}'
curl -X POST localhost:8080/api/candidates/1/shadow -H 'Content-Type: application/json' \
     -d '{"params":{"monthFrom":"2025-11","monthTo":"2026-02"}}'
curl -X POST localhost:8080/api/candidates/1/judge
# 门禁 → 路由 → 回退
curl -X POST localhost:8080/api/candidates/1/gate -H 'Content-Type: application/json' \
     -d '{"paramRange":{"monthFrom":"2025-11","monthTo":"2026-02","orgCode":"ANY","settleStatus":"ANY","diagPrefix":"ANY"},"policyId":1}'
curl -X POST localhost:8080/api/router/query -H 'Content-Type: application/json' \
     -d '{"token":"tok-audit01","gateId":1,"params":{"monthFrom":"2025-11","monthTo":"2026-02"}}'
curl -X POST localhost:8080/api/gates/1/rollback -H 'Content-Type: application/json' -d '{"reason":"演练"}'
curl localhost:8080/api/ledger?gateId=1
```

演示 SQL 模板与两组参数可通过 `GET /api/demo/sql` 查看；`doc/registration.json` 为登记报文示例。

### 3. 可计算的费用快照样例核对

`GET /api/snapshots/{id}/sample` 返回机构级汇总、总额、校验和，并附可手工重算的 SQL。例如用 psql 独立重算（应与接口一致）：

```sql
SELECT COUNT(*), SUM(amount) FROM snap_fee_detail;                 -- 24000 / 6023280.00
SELECT org_code, SUM(amount) FROM snap_fee_detail
 WHERE to_char(settle_date,'YYYY-MM') BETWEEN '2025-11' AND '2026-02'
   AND org_code IN ('ORG001','ORG002') GROUP BY 1;                  -- 80634.20 / 82137.78
SELECT org_code, SUM(amount) FROM snap_fee_daily_org_stale
 WHERE month BETWEEN '2025-11' AND '2026-02' AND org_code='ORG001' GROUP BY 1;  -- 162771.98（缺陷表把 ORG002 并入 ORG001）
```

### 4. 自动化测试

```bash
mvn test   # 16 个测试：改写引擎/参数分级单元测试 + 改写六场景 + 排行模块集成测试（独立 schema verify_it）
```

### 5. 月度费用排行模块验证（本题新增）

#### 5.1 一键演示（推荐）

```bash
curl -X POST http://localhost:8080/api/ranking-demo/run | jq
```

一次调用真实落库跑通六个场景并返回证据（口径、构建、分区、路由决策、影子比较、审批、失效登记相互引用，均可逐条用 API 复查）：

| 场景 | 关键证据字段 | 预期 |
| --- | --- | --- |
| ① 频率路由 | `scenario1_frequencyRouting` | 无构建时连续 3 次请求均 `ON_DEMAND_SCAN`，第 3 次达高频阈值（默认 3）并产生 `FREQUENCY_HINT` 预计算提示 |
| ② 预计算 vs 现扫 | `scenario2_*` | 基线构建 `READY/100%`；高频请求命中 `PRECOMPUTED`；带临时筛选（`minAmount`+剔除丙类）只能 `ON_DEMAND_SCAN`；深分页命中预计算且决策理由注明现扫路径的资源代价 |
| ③ 权限过滤 | `scenario3_permissionFilter` | 机构账号精确分区缺失时走 `FALLBACK_VERIFIED`（同口径同快照全省分区+授权行级过滤），无越权机构泄露；有精确分区时直接 `PRECOMPUTED` |
| ④ 构建延迟 | `scenario4_buildDelay` | HIGH 构建保持 `RUNNING` 时，`FAST+AUTO` 请求返回可解释 `WAITING`（含进度/原因，不交付旧口径）；`waitStrategy=SCAN` 走当前口径现扫；`/advance` 后命中 `PRECOMPUTED` |
| ⑤ 局部分类调整 | `scenario5_localCategoryAdjustment` | v2 仅把 G003/G007 从谈判药调入乙类；失效登记受影响分类精确为 `[乙类,谈判药]`、药品 `[G003,G007]`、月份 `[2025-11]`，仅 6 个分区失效，2025-08~10 封账月保持 READY；重建后登记册 `REBUILT`，路由只服务新口径 |
| ⑥ 并列规则审批 | `scenario6_tieRuleApproval` | v3 改稠密并列，影子比较 `verdict=BLOCKED`、`tieRuleDiffers=true`、定位并列名次差异并自动创建 PENDING 发布审批；审批通过留痕；并列规则相同的对照比较不触发审批 |

#### 5.2 手工分步验证（curl 示例）

```bash
# 1) 查看生效口径与四要素差异
curl -s http://localhost:8080/api/metric-calibers/active | jq
# 2) 以生效口径构建 2025-08 药品/医院/科室排行（holdMonths 模拟延迟）
curl -s -X POST http://localhost:8080/api/ranking-builds -H 'Content-Type: application/json' -d '{
  "caliberId": 1, "priority": "HIGH", "holdMonths": ["2025-08"],
  "buildSpec": {"monthFrom":"2025-08","monthTo":"2025-08","dimensions":["DRUG","ORG","DEPT"],"orgScopes":["*"]}}'
# 3) 高优先级请求 → 可解释等待（不交付旧口径）
curl -s -X POST http://localhost:8080/api/ranking-router/query -H 'Content-Type: application/json' -d '{
  "token":"tok-drg01","month":"2025-08","dimension":"DRUG","latencyTarget":"FAST"}' | jq .routedTo,.waiting
# 4) 推进构建后再次请求 → 命中预计算
curl -s -X POST http://localhost:8080/api/ranking-builds/1/advance | jq .status
curl -s -X POST http://localhost:8080/api/ranking-router/query -H 'Content-Type: application/json' -d '{
  "token":"tok-drg01","month":"2025-08","dimension":"DRUG"}' | jq .routedTo
# 5) 机构级账号：授权过滤后的医院排行（精确分区缺失时自动 FALLBACK_VERIFIED）
curl -s -X POST http://localhost:8080/api/ranking-router/query -H 'Content-Type: application/json' -d '{
  "token":"tok-audit01","month":"2025-08","dimension":"ORG","topN":20}' | jq .routedTo,.rows
# 6) 临时筛选 → 查询时扫描，无需等待构建
curl -s -X POST http://localhost:8080/api/ranking-router/query -H 'Content-Type: application/json' -d '{
  "token":"tok-drg01","month":"2025-08","dimension":"DRUG",
  "adhocFilters":{"minAmount":300,"extraExcludedCategories":["丙类"]}}' | jq .routedTo,.rowCount
# 7) 局部口径调整 → 失效登记 → 重建
curl -s -X POST http://localhost:8080/api/metric-calibers -H 'Content-Type: application/json' -d '{
  "changeNote":"G003/G007 调入乙类",
  "categoryRules":[{"from":"谈判药","to":"乙类","drugCodes":["G003","G007"]}]}'
curl -s -X POST http://localhost:8080/api/metric-calibers/2/activate | jq .diff
curl -s -X POST http://localhost:8080/api/caliber-invalidations -H 'Content-Type: application/json' -d '{
  "baseCaliberId":1,"targetCaliberId":2,"policyEffectiveMonth":"2025-11","detail":"局部调整"}' | jq
curl -s -X POST http://localhost:8080/api/caliber-invalidations/1/rebuild -H 'Content-Type: application/json' -d '{}' | jq .status
```

#### 5.3 可手工重算的排行核对（psql）

药品月度排行口径（剔除拒付，按金额降序）与下面 SQL 等价，可与 `/api/ranking-builds/partitions/{id}` 的 entries 逐位核对：

```sql
SELECT f.drug_code, g.drug_name, SUM(f.amount) AS amount, COUNT(*) AS cnt
FROM snap_fee_detail f JOIN snap_dim_drug g ON g.drug_code = f.drug_code
WHERE to_char(f.settle_date,'YYYY-MM') = '2025-08' AND f.settle_status <> 'REJECTED'
GROUP BY 1, 2 ORDER BY amount DESC, f.drug_code;   -- 竞赛/稠密/顺序名次由服务按口径 tieRules 赋名
```

## 实际验证结论（本容器内真实执行）

- `mvn test`：**16 个测试全部通过**——改写引擎/参数分级单元测试（9）、改写六场景集成测试（4，独立 schema `verify_it`）、排行模块集成测试（3，含局部分类调整/构建延迟/并列审批/权限过滤全链路）。
- `POST /api/ranking-demo/run`：HTTP 200，六场景证据全部符合预期——频率路由三次现扫后给高频提示；基线构建 READY 后高频请求 PRECOMPUTED、临时筛选 ON_DEMAND_SCAN；权限兜底 FALLBACK_VERIFIED 无越权泄露；HIGH 构建延迟返回 WAITING（不交付旧口径）、推进后 PRECOMPUTED；局部分类调整的失效范围精确到 `[乙类,谈判药]`/`[G003,G007]`/`[2025-11]`、仅 6 个分区失效且封账月保留，重建后只服务新口径；并列规则变化 BLOCKED 并强制发布审批。
- `POST /api/demo/run`：HTTP 200，原查询改写六场景无回归（窄/宽计划哈希不同、stale 候选 BLOCK、在途请求 PENDING_REVIEW、回退后路由 ORIGINAL、刷新后门禁 BLOCKED）。
- 快照样例经 psql 独立重算核对一致：24000 行、总额 6023280.00、校验和 `77df7aacfc8eeb2de9a461732d0cb4e6`。
- `java -jar target/fee-rewrite-verify-1.0.0.jar` 独立启动正常，Flyway 成功应用 V1~V3；Swagger UI 与 OpenAPI 文档可访问，共 **55 个路径**（其中排行模块 22 个）。
- 手工接口抽查：非法 token 返回 401；机构账号 audit02 经行级过滤仅见 ORG001~003；口径四要素 diff 与审批留痕接口返回正确。
- Dockerfile 与 docker-compose.yml 经静态核验（本容器无 docker 守护进程，未实际构建镜像）；compose 配置含健康检查与依赖编排，镜像标签使用 `maven:3.9-eclipse-temurin-25` / `eclipse-temurin:25-jre` / `postgres:18-alpine`。

## 设计说明与边界

- **登记模板族**：改写等价性靠机械变换保证，因此登记仅接受平台模板族（`FROM snap_fee_detail d` + 行式 AND 条件 + ORDER BY），见 `SqlRewrite`；非模板族 SQL 返回 422。
- **参数签名** = 参数分级(NARROW/MEDIUM/WIDE) + 参数值哈希；计划哈希 = 计划节点类型+关系名的 SHA-256。诊断按 (登记, 参数签名, 计划哈希) 唯一建档。
- **预聚合候选**仅在 `settleStatus/diagPrefix` 为空时保证等价，门禁强制绑定 `NULL_ONLY`。
- **锁等待**：影子执行期间以 25ms 间隔采样 `pg_stat_activity` 的 Lock 等待事件累计近似值。
- **演示规模**：明细 24000 行（ORG000 占 55% 以制造计划倾斜）；主键集合全量落库仅为演示便利，生产应改为哈希+抽样。
- **冻结副本形态**：演示中 snap_* 表与基表同实例（生产可替换为只读备库/快照schema）；freeze 会重建 snap_* 表并使旧结论失效。
- **排行口径绑定**：每个排行分区冗余保存口径四要素摘要（`caliber_digest`）与快照 id，结果只能由“口径+快照”双键解释；口径非 ACTIVE 不能服务请求，分区被失效登记后严禁作为旧口径交付。
- **失效精度边界**：分类/药品级规则变化精确到分类与药品；结算状态剔除、排序、并列规则是跨分类变化，按全分类登记。排行分区是 月份×维度×机构范围 的最小失效单元（受影响药品进入任意维度聚合），因此受影响月份的三个维度分区统一失效，但登记保留分类/药品级精确范围；政策生效月之前的封账月报不失效、不混口径。
- **频率阈值**：`verify.ranking.hot-frequency-threshold`（默认 3），同一（口径,月份,维度）请求计数达到阈值即视为高频，路由优先预计算并对现扫请求给构建提示。
- **构建延迟为模拟**：`holdMonths` 让指定月份分区停留 BUILDING，用于复现“构建未完成的高优先级请求”；生产可替换为真实异步构建队列，等待/现扫决策逻辑不变。
- 本服务不修改 task-base 基线数据，所有验证对象均为运行时新建的 snap_* 表与元数据。
