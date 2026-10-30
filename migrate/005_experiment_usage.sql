-- 由获准的数据库维护步骤执行；应用启动不自动建表。
CREATE TABLE IF NOT EXISTS racing_experiment_usage (
    usage_id uuid PRIMARY KEY,
    service_principal text NOT NULL,
    environment text NOT NULL,
    scope_instance_id text NOT NULL,
    owner_namespace text NOT NULL,
    owner_kind text NOT NULL,
    owner_scope_instance_id text NOT NULL,
    root_id text NOT NULL,
    root_run_id text NOT NULL,
    current_run_id text NOT NULL,
    call_id text NOT NULL,
    experiment_id text NOT NULL,
    usage_attempt_id uuid NOT NULL,
    assignment_id uuid,
    source_kind text NOT NULL,
    configuration_version bigint,
    adopted_value_json text NOT NULL,
    differs_from_assignment boolean NOT NULL,
    difference_reason text,
    safety_default boolean NOT NULL,
    safety_default_reason text,
    content_checksum text,
    content_check_passed boolean,
    content_check_reason text,
    occurred_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (service_principal, experiment_id, usage_attempt_id),
    CHECK (call_id <> ''),
    CHECK (source_kind IN ('EXPERIMENT', 'ORDINARY_DEFAULT', 'ENDED_FIXED')),
    CHECK (
        (source_kind = 'ENDED_FIXED' AND assignment_id IS NULL)
        OR (source_kind <> 'ENDED_FIXED' AND assignment_id IS NOT NULL)
    ),
    CHECK (configuration_version IS NULL OR configuration_version > 0),
    CHECK (char_length(adopted_value_json) > 0),
    CHECK (
        (differs_from_assignment AND difference_reason IS NOT NULL AND difference_reason <> '')
        OR (NOT differs_from_assignment AND difference_reason IS NULL)
    ),
    CHECK (
        (safety_default AND safety_default_reason IS NOT NULL AND safety_default_reason <> '')
        OR (NOT safety_default AND safety_default_reason IS NULL)
    ),
    CHECK (content_checksum IS NULL OR content_checksum <> ''),
    CHECK (
        (content_check_passed IS NULL AND content_check_reason IS NULL)
        OR (content_check_passed IS TRUE AND (content_check_reason IS NULL OR content_check_reason <> ''))
        OR (content_check_passed IS FALSE AND content_check_reason IS NOT NULL AND content_check_reason <> '')
    )
);

CREATE TABLE IF NOT EXISTS racing_experiment_result_feedback (
    feedback_id uuid PRIMARY KEY,
    usage_id uuid NOT NULL UNIQUE REFERENCES racing_experiment_usage (usage_id),
    result_json jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (jsonb_typeof(result_json) = 'object')
);
