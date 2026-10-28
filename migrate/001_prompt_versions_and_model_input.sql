-- PostgreSQL：正文、集合、引用和原始模型输入。执行由部署流程明确授权。
BEGIN;

CREATE TABLE racing_prompt (
    environment TEXT NOT NULL,
    scope_instance_id TEXT NOT NULL,
    prompt_id UUID NOT NULL,
    name TEXT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    default_version BIGINT,
    created_by TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    modified_by TEXT NOT NULL,
    modified_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_action TEXT NOT NULL,
    PRIMARY KEY (environment, scope_instance_id, prompt_id),
    UNIQUE (environment, scope_instance_id, name)
);

CREATE TABLE racing_prompt_version (
    environment TEXT NOT NULL,
    scope_instance_id TEXT NOT NULL,
    prompt_id UUID NOT NULL,
    version BIGINT NOT NULL CHECK (version > 0),
    purpose TEXT NOT NULL,
    body_type TEXT NOT NULL CHECK (body_type IN ('FIXED', 'TEMPLATE')),
    body_text TEXT NOT NULL,
    variables_json JSONB NOT NULL CHECK (jsonb_typeof(variables_json) = 'array'),
    body_sha256 TEXT NOT NULL CHECK (body_sha256 ~ '^[0-9a-f]{64}$'),
    readable_scopes_json JSONB NOT NULL CHECK (jsonb_typeof(readable_scopes_json) = 'array'),
    authorization_revision BIGINT NOT NULL DEFAULT 1 CHECK (authorization_revision > 0),
    import_origin_json JSONB,
    created_by TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (environment, scope_instance_id, prompt_id, version),
    FOREIGN KEY (environment, scope_instance_id, prompt_id)
        REFERENCES racing_prompt (environment, scope_instance_id, prompt_id)
);

ALTER TABLE racing_prompt ADD CONSTRAINT racing_prompt_default_version_fk
    FOREIGN KEY (environment, scope_instance_id, prompt_id, default_version)
    REFERENCES racing_prompt_version (environment, scope_instance_id, prompt_id, version);

CREATE TABLE racing_prompt_set (
    environment TEXT NOT NULL,
    scope_instance_id TEXT NOT NULL,
    set_id UUID NOT NULL,
    name TEXT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    created_by TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    modified_by TEXT NOT NULL,
    modified_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_action TEXT NOT NULL,
    PRIMARY KEY (environment, scope_instance_id, set_id),
    UNIQUE (environment, scope_instance_id, name)
);

CREATE TABLE racing_prompt_set_version (
    environment TEXT NOT NULL,
    scope_instance_id TEXT NOT NULL,
    set_id UUID NOT NULL,
    version BIGINT NOT NULL CHECK (version > 0),
    -- 成员明确包含片段名、正文编号、具体版本及预期用途；引用服务同时登记外键引用。
    members_json JSONB NOT NULL CHECK (jsonb_typeof(members_json) = 'array'),
    created_by TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (environment, scope_instance_id, set_id, version),
    FOREIGN KEY (environment, scope_instance_id, set_id)
        REFERENCES racing_prompt_set (environment, scope_instance_id, set_id)
);

CREATE TABLE racing_prompt_default_set (
    environment TEXT NOT NULL,
    scope_instance_id TEXT NOT NULL,
    business_key TEXT NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    set_environment TEXT NOT NULL,
    set_scope_instance_id TEXT NOT NULL,
    set_id UUID NOT NULL,
    set_version BIGINT NOT NULL CHECK (set_version > 0),
    modified_by TEXT NOT NULL,
    modified_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_action TEXT NOT NULL,
    PRIMARY KEY (environment, scope_instance_id, business_key),
    FOREIGN KEY (set_environment, set_scope_instance_id, set_id, set_version)
        REFERENCES racing_prompt_set_version (environment, scope_instance_id, set_id, version)
);

CREATE TABLE racing_prompt_root_selection (
    selection_id UUID PRIMARY KEY,
    environment TEXT NOT NULL,
    scope_instance_id TEXT NOT NULL,
    owner_namespace TEXT NOT NULL,
    owner_kind TEXT NOT NULL,
    owner_scope_instance_id TEXT NOT NULL,
    root_id TEXT NOT NULL,
    selection_key TEXT NOT NULL,
    selection_kind TEXT NOT NULL CHECK (selection_kind IN ('PROMPT', 'SET')),
    resource_environment TEXT NOT NULL,
    resource_scope_instance_id TEXT NOT NULL,
    prompt_id UUID,
    prompt_version BIGINT,
    set_id UUID,
    set_version BIGINT,
    source_json JSONB NOT NULL CHECK (jsonb_typeof(source_json) = 'object'),
    created_by TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (environment, scope_instance_id, owner_namespace, owner_kind,
            owner_scope_instance_id, root_id, selection_key),
    CHECK ((selection_kind = 'PROMPT' AND prompt_id IS NOT NULL AND prompt_version IS NOT NULL
             AND set_id IS NULL AND set_version IS NULL)
        OR (selection_kind = 'SET' AND set_id IS NOT NULL AND set_version IS NOT NULL
             AND prompt_id IS NULL AND prompt_version IS NULL)),
    FOREIGN KEY (resource_environment, resource_scope_instance_id, prompt_id, prompt_version)
        REFERENCES racing_prompt_version (environment, scope_instance_id, prompt_id, version),
    FOREIGN KEY (resource_environment, resource_scope_instance_id, set_id, set_version)
        REFERENCES racing_prompt_set_version (environment, scope_instance_id, set_id, version)
);

CREATE TABLE racing_prompt_reference (
    reference_id UUID PRIMARY KEY,
    environment TEXT NOT NULL,
    scope_instance_id TEXT NOT NULL,
    -- 引用方的权限与修订由业务在同一事务核对，普通JSON不推导为正文引用。
    owner_environment TEXT NOT NULL,
    owner_scope_instance_id TEXT NOT NULL,
    owner_service TEXT NOT NULL,
    owner_type TEXT NOT NULL,
    owner_id TEXT NOT NULL,
    -- 根选择和输入本身没有可变修订，使用0；草稿等业务使用真实正修订。
    owner_revision BIGINT NOT NULL CHECK (owner_revision >= 0),
    prompt_id UUID,
    prompt_version BIGINT,
    set_id UUID,
    set_version BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((prompt_id IS NOT NULL AND prompt_version IS NOT NULL
             AND set_id IS NULL AND set_version IS NULL)
        OR (set_id IS NOT NULL AND set_version IS NOT NULL
             AND prompt_id IS NULL AND prompt_version IS NULL)),
    FOREIGN KEY (environment, scope_instance_id, prompt_id, prompt_version)
        REFERENCES racing_prompt_version (environment, scope_instance_id, prompt_id, version),
    FOREIGN KEY (environment, scope_instance_id, set_id, set_version)
        REFERENCES racing_prompt_set_version (environment, scope_instance_id, set_id, version)
);

CREATE UNIQUE INDEX racing_prompt_reference_prompt_unique ON racing_prompt_reference
    (owner_environment, owner_scope_instance_id, owner_service, owner_type, owner_id, owner_revision,
     environment, scope_instance_id,
     prompt_id, prompt_version) WHERE prompt_id IS NOT NULL;
CREATE UNIQUE INDEX racing_prompt_reference_set_unique ON racing_prompt_reference
    (owner_environment, owner_scope_instance_id, owner_service, owner_type, owner_id, owner_revision,
     environment, scope_instance_id,
     set_id, set_version) WHERE set_id IS NOT NULL;
CREATE INDEX racing_prompt_reference_prompt_target ON racing_prompt_reference
    (environment, scope_instance_id, prompt_id, prompt_version) WHERE prompt_id IS NOT NULL;
CREATE INDEX racing_prompt_reference_set_target ON racing_prompt_reference
    (environment, scope_instance_id, set_id, set_version) WHERE set_id IS NOT NULL;
CREATE INDEX racing_prompt_reference_owner ON racing_prompt_reference
    (owner_environment, owner_scope_instance_id, owner_service, owner_type, owner_id, owner_revision);

CREATE TABLE racing_model_input (
    record_id UUID PRIMARY KEY,
    service_principal TEXT NOT NULL,
    environment TEXT NOT NULL,
    scope_instance_id TEXT NOT NULL,
    owner_namespace TEXT NOT NULL,
    owner_kind TEXT NOT NULL,
    owner_scope_instance_id TEXT NOT NULL,
    root_id TEXT NOT NULL,
    current_run_id TEXT NOT NULL,
    logical_call_id UUID NOT NULL,
    attempt_id UUID NOT NULL,
    endpoint_name TEXT NOT NULL,
    -- 正文使用TEXT，不能由JSONB重新序列化后冒充实际发送字节。
    body_json TEXT NOT NULL,
    utf8_length BIGINT NOT NULL CHECK (utf8_length >= 0),
    body_sha256 TEXT NOT NULL CHECK (body_sha256 ~ '^[0-9a-f]{64}$'),
    context_json JSONB NOT NULL CHECK (jsonb_typeof(context_json) = 'object'),
    sources_json JSONB NOT NULL CHECK (jsonb_typeof(sources_json) = 'array'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (utf8_length = octet_length(convert_to(body_json, 'UTF8'))),
    UNIQUE (service_principal, attempt_id)
);

CREATE INDEX racing_model_input_root ON racing_model_input
    (environment, scope_instance_id, owner_namespace, owner_kind,
     owner_scope_instance_id, root_id, created_at);
CREATE INDEX racing_model_input_logical_call ON racing_model_input
    (service_principal, logical_call_id, created_at);

COMMIT;
