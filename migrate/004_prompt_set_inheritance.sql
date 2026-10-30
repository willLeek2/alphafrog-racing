-- PostgreSQL：集合版本保存固定基准、本次成员修改和移除名称。
BEGIN;

ALTER TABLE racing_prompt_set_version
    ADD COLUMN base_version_json JSONB,
    ADD COLUMN removed_fragment_names_json JSONB NOT NULL DEFAULT '[]'::JSONB,
    ADD CONSTRAINT racing_prompt_set_version_base_object
        CHECK (base_version_json IS NULL OR jsonb_typeof(base_version_json) = 'object'),
    ADD CONSTRAINT racing_prompt_set_version_removed_array
        CHECK (jsonb_typeof(removed_fragment_names_json) = 'array');

COMMENT ON COLUMN racing_prompt_set_version.members_json IS
    '本版本修改或新增的成员；没有基准时为初始整组，业务读取时沿基准展开';
COMMENT ON COLUMN racing_prompt_set_version.base_version_json IS
    '创建时指定的明确集合版本引用；删除保护由同事务登记的集合引用维护';

COMMIT;
