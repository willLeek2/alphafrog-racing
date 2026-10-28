-- 由获准的数据库维护步骤执行；应用启动不自动建表。
CREATE TABLE IF NOT EXISTS racing_experiment (
    environment text NOT NULL,
    scope_instance_id text NOT NULL,
    experiment_id text NOT NULL,
    name text,
    status text NOT NULL CHECK (status IN ('DRAFT', 'RUNNING', 'PAUSED', 'ENDED')),
    revision bigint NOT NULL CHECK (revision > 0),
    draft_revision bigint NOT NULL CHECK (draft_revision > 0),
    draft_json jsonb NOT NULL,
    published_version bigint,
    final_json jsonb,
    deleted_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (environment, scope_instance_id, experiment_id),
    UNIQUE (environment, scope_instance_id, name)
);

CREATE TABLE IF NOT EXISTS racing_experiment_version (
    environment text NOT NULL,
    scope_instance_id text NOT NULL,
    experiment_id text NOT NULL,
    version bigint NOT NULL CHECK (version > 0),
    configuration_json jsonb NOT NULL,
    total_weight bigint NOT NULL CHECK (total_weight > 0),
    published_by text NOT NULL,
    published_at timestamptz NOT NULL,
    PRIMARY KEY (environment, scope_instance_id, experiment_id, version),
    FOREIGN KEY (environment, scope_instance_id, experiment_id)
        REFERENCES racing_experiment (environment, scope_instance_id, experiment_id)
);

CREATE TABLE IF NOT EXISTS racing_experiment_operation (
    operation_id text PRIMARY KEY,
    environment text NOT NULL,
    scope_instance_id text NOT NULL,
    experiment_id text NOT NULL,
    action text NOT NULL,
    actor text NOT NULL,
    before_revision bigint NOT NULL,
    after_revision bigint NOT NULL,
    configuration_version bigint,
    detail_json jsonb NOT NULL,
    operated_at timestamptz NOT NULL,
    FOREIGN KEY (environment, scope_instance_id, experiment_id)
        REFERENCES racing_experiment (environment, scope_instance_id, experiment_id),
    FOREIGN KEY (environment, scope_instance_id, experiment_id, configuration_version)
        REFERENCES racing_experiment_version (environment, scope_instance_id, experiment_id, version)
);

CREATE INDEX IF NOT EXISTS racing_experiment_status_idx
    ON racing_experiment (environment, scope_instance_id, status, updated_at, experiment_id);
CREATE INDEX IF NOT EXISTS racing_experiment_operation_history_idx
    ON racing_experiment_operation (environment, scope_instance_id, experiment_id, operated_at, operation_id);
