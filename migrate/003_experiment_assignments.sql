-- 先执行实验配置三表；本脚本由获准的数据库维护步骤执行，应用不自动建表。
BEGIN;

CREATE TABLE racing_experiment_assignment (
    assignment_id UUID PRIMARY KEY,
    environment TEXT NOT NULL,
    scope_instance_id TEXT NOT NULL,
    owner_namespace TEXT NOT NULL,
    owner_kind TEXT NOT NULL,
    owner_scope_instance_id TEXT NOT NULL,
    root_id TEXT NOT NULL,
    experiment_id TEXT NOT NULL,
    configuration_version BIGINT CHECK (configuration_version > 0),
    candidate_id TEXT,
    -- 使用TEXT保留业务JSON的原始表示；检查表达式只验证其合法性和支持的类型。
    value_json TEXT NOT NULL CHECK (jsonb_typeof(value_json::jsonb) IN ('number', 'string', 'object')),
    source_kind TEXT NOT NULL CHECK (source_kind IN ('EXPERIMENT', 'ORDINARY_DEFAULT')),
    business_default_source_json TEXT CHECK (business_default_source_json IS NULL
                                             OR business_default_source_json::jsonb IS NOT NULL),
    service_principal TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (environment, scope_instance_id, owner_namespace, owner_kind,
            owner_scope_instance_id, root_id, experiment_id),
    FOREIGN KEY (environment, scope_instance_id, experiment_id)
        REFERENCES racing_experiment (environment, scope_instance_id, experiment_id),
    FOREIGN KEY (environment, scope_instance_id, experiment_id, configuration_version)
        REFERENCES racing_experiment_version (environment, scope_instance_id, experiment_id, version),
    CHECK ((source_kind = 'EXPERIMENT' AND configuration_version IS NOT NULL
            AND candidate_id IS NOT NULL AND candidate_id <> '')
        OR (source_kind = 'ORDINARY_DEFAULT' AND candidate_id IS NULL
            AND business_default_source_json IS NOT NULL))
);

CREATE INDEX racing_experiment_assignment_root ON racing_experiment_assignment
    (environment, scope_instance_id, owner_namespace, owner_kind, owner_scope_instance_id, root_id);

COMMIT;
