-- =====================================================================
-- V3  指标口径版本库 / 排行榜构建器 / 查询路由决策器 /
--     影子排行比较器 / 失效登记册
--
-- 五个组件的状态相互引用：
--   metric_caliber_version（口径版本）
--     └─ ranking_build（构建：绑定口径 + 数据快照）
--          └─ ranking_partition（排行分区：构建产物，可被登记册精确失效）
--   ranking_route_decision（路由决策：引用口径/构建/分区/用户授权）
--   ranking_shadow_compare（影子比较：引用两个口径版本与两侧构建）
--   ranking_approval（发布审批：承接并列规则差异等阻断项）
--   caliber_invalidation_register（失效登记：政策修订 → 月份/分类/分区）
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 指标口径版本库：分类、剔除、排序、并列四类规则，整体版本化
-- ---------------------------------------------------------------------
CREATE TABLE metric_caliber_version (
    id               BIGSERIAL PRIMARY KEY,
    family           TEXT NOT NULL,              -- 口径族，如 fee-ranking
    version          INT  NOT NULL,
    -- 分类规则：把药品/诊断业务分类映射为指标口径分类
    --   [{"from":"谈判药","to":"乙类","drugCodes":[]}]，drugCodes 非空表示仅调整个别药品
    category_rules   JSONB NOT NULL,
    -- 剔除规则：不计入排行的结算状态/药品分类/药品编码
    --   {"settleStatuses":["REJECTED"],"drugCategories":["丙类"],"drugCodes":[]}
    exclusion_rules  JSONB NOT NULL,
    -- 排序规则：{"metric":"AMOUNT","direction":"DESC"}，metric=AMOUNT/COUNT
    sort_rules       JSONB NOT NULL,
    -- 并列规则：
    --   strategy=COMPETITION(竞赛并列 1,1,3) / DENSE(稠密并列 1,1,2) / SEQUENTIAL(不并列 1,2,3)
    --   tieBreaker=CODE_ASC（同额时按编码稳定排序）
    tie_rules        JSONB NOT NULL,
    -- 该口径允许的排行维度与授权过滤粒度（构建可用范围据此校验）
    scope_rules      JSONB NOT NULL,             -- {"dimensions":["ORG","DEPT","DRUG"],"authGranularities":["PROVINCE","ORG"]}
    status           TEXT NOT NULL DEFAULT 'DRAFT', -- DRAFT / ACTIVE / RETIRED
    change_note      TEXT,
    created_by       TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    activated_at     TIMESTAMPTZ,
    UNIQUE (family, version)
);

-- ---------------------------------------------------------------------
-- 2. 排行榜构建器：一次构建绑定一个口径版本与一个冻结快照，
--    记录输入快照、构建进度与可用范围；产物按 月份×维度×机构范围 分区
-- ---------------------------------------------------------------------
CREATE TABLE ranking_build (
    id                 BIGSERIAL PRIMARY KEY,
    caliber_id         BIGINT NOT NULL REFERENCES metric_caliber_version(id),
    snapshot_id        BIGINT NOT NULL REFERENCES data_snapshot(id),
    -- 输入快照（冗余记录行数/校验和，便于解释“结果代表哪份数据”）
    input_snapshot     JSONB NOT NULL,
    -- 构建参数：月份区间、维度、机构范围、Top N、授权粒度
    build_spec         JSONB NOT NULL,
    -- 可用范围：构建成功后由实际就绪分区汇总，路由据此判断能否命中预计算
    availability       JSONB NOT NULL DEFAULT '{}',
    -- PENDING 已登记 / RUNNING 构建中 / READY 全部分区就绪 /
    -- PARTIAL_READY 部分分区就绪（其余失败或失效）/ FAILED / INVALIDATED
    status             TEXT NOT NULL DEFAULT 'PENDING',
    priority           TEXT NOT NULL DEFAULT 'NORMAL', -- NORMAL / HIGH（高优先级构建影响路由等待策略）
    progress_percent   INT  NOT NULL DEFAULT 0,
    progress_detail    JSONB NOT NULL DEFAULT '[]',    -- [{"partitionKey":"2025-11|DRUG|*","status":"READY",...}]
    -- 可解释的等待/失败信息（构建未完成时路由据此返回 WAITING）
    status_reason      TEXT,
    requested_by       TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at         TIMESTAMPTZ,
    finished_at        TIMESTAMPTZ
);

CREATE INDEX idx_ranking_build_caliber ON ranking_build (caliber_id);
CREATE INDEX idx_ranking_build_snapshot ON ranking_build (snapshot_id);
CREATE INDEX idx_ranking_build_status ON ranking_build (status);

-- 排行分区：构建的最小失效/路由单元（月份 × 维度 × 机构范围）
CREATE TABLE ranking_partition (
    id               BIGSERIAL PRIMARY KEY,
    build_id         BIGINT NOT NULL REFERENCES ranking_build(id),
    caliber_id       BIGINT NOT NULL REFERENCES metric_caliber_version(id),
    snapshot_id      BIGINT NOT NULL REFERENCES data_snapshot(id),
    month            TEXT NOT NULL,             -- YYYY-MM
    dimension        TEXT NOT NULL,             -- ORG（医院）/ DEPT（科室=诊断分类）/ DRUG（药品）
    org_scope        TEXT NOT NULL DEFAULT '*', -- '*' 全省，或具体机构编码（授权过滤粒度）
    -- READY 可用 / BUILDING 构建中 / FAILED / INVALIDATED 被登记册精确失效
    status           TEXT NOT NULL DEFAULT 'BUILDING',
    invalidation_id  BIGINT,                    -- 被哪条失效登记命中（回填）
    row_count        BIGINT NOT NULL DEFAULT 0,
    total_amount     NUMERIC(18,2),
    -- 完整排行（含名次/并列标记），分区内全量保存（演示规模）
    entries          JSONB NOT NULL DEFAULT '[]',
    -- 构建所用口径四要素快照（防止口径行被更新后无法解释历史结果）
    caliber_digest   JSONB NOT NULL,
    built_at         TIMESTAMPTZ,
    UNIQUE (build_id, month, dimension, org_scope)
);

CREATE INDEX idx_ranking_partition_lookup
    ON ranking_partition (caliber_id, snapshot_id, month, dimension, org_scope);
CREATE INDEX idx_ranking_partition_status ON ranking_partition (status);

-- ---------------------------------------------------------------------
-- 3. 查询路由决策器：每次排行请求的决策留痕（口径/时间/授权/时效目标 → 路径）
-- ---------------------------------------------------------------------
CREATE TABLE ranking_route_decision (
    id               BIGSERIAL PRIMARY KEY,
    caliber_id       BIGINT NOT NULL REFERENCES metric_caliber_version(id),
    build_id         BIGINT REFERENCES ranking_build(id),      -- 命中/等待的构建，扫描路径可为空
    partition_id     BIGINT REFERENCES ranking_partition(id),  -- 实际服务的预计算分区
    username         TEXT NOT NULL,
    role             TEXT NOT NULL,
    org_code         TEXT,                                      -- NULL=省级账号
    -- 归一化后的请求：月份/维度/机构/TopN/时效目标/临时筛选/深分页
    request_spec     JSONB NOT NULL,
    -- PRECOMPUTED 预计算结果 / ON_DEMAND_SCAN 查询时扫描明细 /
    -- WAITING 构建未完成的可解释等待 / FALLBACK_VERIFIED 经验证的另一条路径 / REJECTED
    routed_to        TEXT NOT NULL,
    status           TEXT NOT NULL,             -- SERVED / WAITING / REJECTED
    -- 决策依据（逐条可解释）：频率、可接受等待、口径变更影响面、授权粒度
    decision_reasons JSONB NOT NULL,
    -- 临时筛选（adhoc 过滤）导致预计算不可直接复用时的说明
    adhoc_filters    JSONB NOT NULL DEFAULT '[]',
    row_count        BIGINT,
    elapsed_ms       BIGINT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ranking_route_caliber ON ranking_route_decision (caliber_id);
CREATE INDEX idx_ranking_route_user ON ranking_route_decision (username, created_at);

-- ---------------------------------------------------------------------
-- 4. 影子排行比较器：两个口径版本在同一快照上的排行差异
-- ---------------------------------------------------------------------
CREATE TABLE ranking_shadow_compare (
    id                  BIGSERIAL PRIMARY KEY,
    base_caliber_id     BIGINT NOT NULL REFERENCES metric_caliber_version(id),
    target_caliber_id   BIGINT NOT NULL REFERENCES metric_caliber_version(id),
    base_build_id       BIGINT NOT NULL REFERENCES ranking_build(id),
    target_build_id     BIGINT NOT NULL REFERENCES ranking_build(id),
    snapshot_id         BIGINT NOT NULL REFERENCES data_snapshot(id),
    month               TEXT NOT NULL,
    dimension           TEXT NOT NULL,
    org_scope           TEXT NOT NULL DEFAULT '*',
    top_n               INT  NOT NULL,
    -- 口径四要素差异：[{"element":"CATEGORY|EXCLUSION|SORT|TIE","changed":true,"detail":{...}}]
    caliber_diff        JSONB NOT NULL,
    -- 前 N 名差异（编码集合/顺序/金额/并列名次/可见机构）
    topn_diff           JSONB NOT NULL,
    -- 可见机构差异（授权过滤后仍能看到的机构集合），仅 ORG 维度有值
    visible_org_diff    JSONB,
    -- 并列规则是否不同；为 true 时比较结论必须转发布审批，不得自动通过
    tie_rule_differs    BOOLEAN NOT NULL DEFAULT FALSE,
    -- MATCH 完全一致 / DIFF 有差异 / BLOCKED 需审批（并列规则不同等）
    verdict             TEXT NOT NULL,
    approval_id         BIGINT,                 -- 转入的发布审批
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ranking_shadow_calibers
    ON ranking_shadow_compare (base_caliber_id, target_caliber_id);

-- 发布审批：承接影子比较阻断项（尤其并列规则差异），审批后新口径才可生效/发布
CREATE TABLE ranking_approval (
    id               BIGSERIAL PRIMARY KEY,
    compare_id       BIGINT NOT NULL REFERENCES ranking_shadow_compare(id),
    target_caliber_id BIGINT NOT NULL REFERENCES metric_caliber_version(id),
    -- PENDING / APPROVED / REJECTED
    status           TEXT NOT NULL DEFAULT 'PENDING',
    -- 触发审批的阻断项，如 ["TIE_RULE_CHANGED: COMPETITION → DENSE"]
    blocking_items   JSONB NOT NULL,
    submitted_by     TEXT,
    reviewed_by      TEXT,
    review_comment   TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_at      TIMESTAMPTZ
);

-- ---------------------------------------------------------------------
-- 5. 失效登记册：政策修订传播到的月份、药品分类与排行分区（精确到分区）
-- ---------------------------------------------------------------------
CREATE TABLE caliber_invalidation_register (
    id               BIGSERIAL PRIMARY KEY,
    -- 触发失效的口径更替（旧版本 → 新版本，同属一个口径族）
    base_caliber_id  BIGINT NOT NULL REFERENCES metric_caliber_version(id),
    target_caliber_id BIGINT NOT NULL REFERENCES metric_caliber_version(id),
    policy_effective_month TEXT NOT NULL,       -- 政策修订生效月（YYYY-MM）
    -- 受影响的药品分类（政策只影响部分分类时只登记这些分类；空数组表示全部分类）
    affected_categories JSONB NOT NULL,
    -- 受影响的具体药品编码（局部分类调整时非空，支持单药品级精确失效）
    affected_drug_codes  JSONB NOT NULL DEFAULT '[]',
    -- 规则要素级影响面：[ {"element":"CATEGORY","detail":...}, ...]
    affected_elements JSONB NOT NULL,
    -- 传播到的月份（生效月起、快照覆盖的所有月份；历史已封账月份同样标记待重建）
    affected_months  JSONB NOT NULL,
    -- 命中的排行分区清单：[{"buildId":..,"partitionId":..,"month":..,"dimension":..,"orgScope":..}]
    affected_partitions JSONB NOT NULL DEFAULT '[]',
    -- OPEN 已登记待重建 / REBUILT 全部受影响分区已重建 / PARTIAL_REBUILT 部分重建
    status           TEXT NOT NULL DEFAULT 'OPEN',
    detail           TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    rebuilt_at       TIMESTAMPTZ
);

CREATE INDEX idx_invalidation_caliber ON caliber_invalidation_register (target_caliber_id);
CREATE INDEX idx_invalidation_status ON caliber_invalidation_register (status);

-- 分区失效回填登记册外键（分区表先建，这里补约束）
ALTER TABLE ranking_partition
    ADD CONSTRAINT fk_partition_invalidation
    FOREIGN KEY (invalidation_id) REFERENCES caliber_invalidation_register(id);

-- ---------------------------------------------------------------------
-- 6. 种子：默认排行口径 v1（生效）与排行权限策略 v1
-- ---------------------------------------------------------------------
INSERT INTO metric_caliber_version
    (family, version, category_rules, exclusion_rules, sort_rules, tie_rules, scope_rules,
     status, change_note, activated_at)
VALUES
    ('fee-ranking', 1, '[]'::jsonb,
     '{"settleStatuses":["REJECTED"],"drugCategories":[],"drugCodes":[]}'::jsonb,
     '{"metric":"AMOUNT","direction":"DESC"}'::jsonb,
     '{"strategy":"COMPETITION","tieBreaker":"CODE_ASC"}'::jsonb,
     '{"dimensions":["ORG","DEPT","DRUG"],"authGranularities":["PROVINCE","ORG"]}'::jsonb,
     'ACTIVE', '初始口径：剔除拒付费用，按金额降序，竞赛并列（1,1,3），同额按编码升序', now());

INSERT INTO permission_policy (family, version, rules, status) VALUES
 ('fee-ranking', 1,
  '[{"role":"AUDIT","allowedOrgs":["ORG001","ORG002","ORG003"]},
    {"role":"DRG","allowedOrgs":"*"},
    {"role":"BUDGET","allowedOrgs":"*"}]'::jsonb,
  'ACTIVE');
