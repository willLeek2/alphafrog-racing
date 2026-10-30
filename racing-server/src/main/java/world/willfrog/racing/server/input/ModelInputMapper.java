package world.willfrog.racing.server.input;

import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.*;

/** 唯一尝试先插入后锁读，只有指定唯一键冲突可复用，其他约束失败交给事务回滚。 */
@Mapper
public interface ModelInputMapper {
    String COLUMNS = """
            record_id::text AS record_id, service_principal, environment, scope_instance_id,
            owner_namespace, owner_kind, owner_scope_instance_id, root_id, current_run_id,
            logical_call_id::text AS logical_call_id, attempt_id::text AS attempt_id, endpoint_name,
            body_json, utf8_length, body_sha256, context_json::text AS context_json,
            sources_json::text AS sources_json, created_at
            """;

    @Insert("""
            INSERT INTO racing_model_input (
                record_id, service_principal, environment, scope_instance_id, owner_namespace,
                owner_kind, owner_scope_instance_id, root_id, current_run_id, logical_call_id,
                attempt_id, endpoint_name, body_json, utf8_length, body_sha256, context_json, sources_json)
            VALUES (
                CAST(#{row.recordId} AS uuid), #{row.servicePrincipal}, #{row.environment},
                #{row.scopeInstanceId}, #{row.ownerNamespace}, #{row.ownerKind}, #{row.ownerScopeInstanceId},
                #{row.rootId}, #{row.currentRunId}, CAST(#{row.logicalCallId} AS uuid),
                CAST(#{row.attemptId} AS uuid), #{row.endpointName}, #{row.bodyJson}, #{row.utf8Length},
                #{row.bodySha256}, CAST(#{row.contextJson} AS jsonb), CAST(#{row.sourcesJson} AS jsonb))
            ON CONFLICT (service_principal, attempt_id) DO NOTHING
            """)
    int insertIfAbsent(@Param("row") ModelInputRow row);

    @Select("SELECT " + COLUMNS + " FROM racing_model_input WHERE service_principal = #{principal} AND attempt_id = CAST(#{attemptId} AS uuid) FOR UPDATE")
    @ConstructorArgs({@Arg(column="record_id", javaType=String.class),
            @Arg(column="service_principal", javaType=String.class),
            @Arg(column="environment", javaType=String.class),
            @Arg(column="scope_instance_id", javaType=String.class),
            @Arg(column="owner_namespace", javaType=String.class),
            @Arg(column="owner_kind", javaType=String.class),
            @Arg(column="owner_scope_instance_id", javaType=String.class),
            @Arg(column="root_id", javaType=String.class),
            @Arg(column="current_run_id", javaType=String.class),
            @Arg(column="logical_call_id", javaType=String.class),
            @Arg(column="attempt_id", javaType=String.class),
            @Arg(column="endpoint_name", javaType=String.class),
            @Arg(column="body_json", javaType=String.class),
            @Arg(column="utf8_length", javaType=long.class),
            @Arg(column="body_sha256", javaType=String.class),
            @Arg(column="context_json", javaType=String.class),
            @Arg(column="sources_json", javaType=String.class),
            @Arg(column="created_at", javaType=OffsetDateTime.class)})
    ModelInputRow lockAttempt(@Param("principal") String principal, @Param("attemptId") String attemptId);

    @Select("SELECT " + COLUMNS + " FROM racing_model_input WHERE record_id = CAST(#{recordId} AS uuid)")
    @ConstructorArgs({@Arg(column="record_id", javaType=String.class),
            @Arg(column="service_principal", javaType=String.class),
            @Arg(column="environment", javaType=String.class),
            @Arg(column="scope_instance_id", javaType=String.class),
            @Arg(column="owner_namespace", javaType=String.class),
            @Arg(column="owner_kind", javaType=String.class),
            @Arg(column="owner_scope_instance_id", javaType=String.class),
            @Arg(column="root_id", javaType=String.class),
            @Arg(column="current_run_id", javaType=String.class),
            @Arg(column="logical_call_id", javaType=String.class),
            @Arg(column="attempt_id", javaType=String.class),
            @Arg(column="endpoint_name", javaType=String.class),
            @Arg(column="body_json", javaType=String.class),
            @Arg(column="utf8_length", javaType=long.class),
            @Arg(column="body_sha256", javaType=String.class),
            @Arg(column="context_json", javaType=String.class),
            @Arg(column="sources_json", javaType=String.class),
            @Arg(column="created_at", javaType=OffsetDateTime.class)})
    ModelInputRow findById(@Param("recordId") String recordId);

    @Select("SELECT " + COLUMNS + " FROM racing_model_input WHERE service_principal = #{principal} AND attempt_id = CAST(#{attemptId} AS uuid)")
    @ConstructorArgs({@Arg(column="record_id", javaType=String.class),
            @Arg(column="service_principal", javaType=String.class),
            @Arg(column="environment", javaType=String.class),
            @Arg(column="scope_instance_id", javaType=String.class),
            @Arg(column="owner_namespace", javaType=String.class),
            @Arg(column="owner_kind", javaType=String.class),
            @Arg(column="owner_scope_instance_id", javaType=String.class),
            @Arg(column="root_id", javaType=String.class),
            @Arg(column="current_run_id", javaType=String.class),
            @Arg(column="logical_call_id", javaType=String.class),
            @Arg(column="attempt_id", javaType=String.class),
            @Arg(column="endpoint_name", javaType=String.class),
            @Arg(column="body_json", javaType=String.class),
            @Arg(column="utf8_length", javaType=long.class),
            @Arg(column="body_sha256", javaType=String.class),
            @Arg(column="context_json", javaType=String.class),
            @Arg(column="sources_json", javaType=String.class),
            @Arg(column="created_at", javaType=OffsetDateTime.class)})
    ModelInputRow findAttempt(@Param("principal") String principal, @Param("attemptId") String attemptId);
}
