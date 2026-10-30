package world.willfrog.racing.server.prompt;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/**
 * 固定SQL映射，仅绑定数据值；目标行锁与业务写入共享当前事务。
 */
@Mapper
public interface PromptMapper {

    @Select("""
        <script>SELECT environment,scope_instance_id,prompt_id::text AS id,name,revision,created_by,created_at,modified_by,modified_at,last_action,default_version FROM racing_prompt WHERE environment=#{environment} AND scope_instance_id=#{scope} AND prompt_id=CAST(#{id} AS UUID)<if test="lock"> FOR UPDATE</if></script>
        """)
    Map<String, Object> getPrompt(Map<String, Object> parameters);

    @Select("""
        SELECT environment,scope_instance_id,prompt_id::text AS id,name,revision,created_by,created_at,modified_by,modified_at,last_action,default_version FROM racing_prompt WHERE environment=#{environment} AND scope_instance_id=#{scope} AND name=#{name}
        """)
    Map<String, Object> findPromptByName(Map<String, Object> parameters);

    @Insert("""
        INSERT INTO racing_prompt(environment,scope_instance_id,prompt_id,name,revision,created_by,modified_by,last_action) VALUES(#{environment},#{scope},CAST(#{id} AS UUID),#{name},#{revision},#{principal},#{principal},#{action}) ON CONFLICT (environment,scope_instance_id,name) DO NOTHING
        """)
    int insertPrompt(Map<String, Object> parameters);

    @Update("""
        UPDATE racing_prompt SET name=#{name},revision=#{revision},modified_by=#{principal},modified_at=now(),last_action=#{action},default_version=#{defaultVersion} WHERE environment=#{environment} AND scope_instance_id=#{scope} AND prompt_id=CAST(#{id} AS UUID) AND revision=#{expected}
        """)
    int updatePrompt(Map<String, Object> parameters);

    @Select("""
        <script>SELECT environment,scope_instance_id,prompt_id::text AS id,name,revision,created_by,created_at,modified_by,modified_at,last_action,default_version FROM racing_prompt WHERE environment=#{environment} AND scope_instance_id=#{scope}<if test="name != ''"> AND strpos(name,#{name})&gt;0</if><if test="purpose != ''"> AND EXISTS(SELECT 1 FROM racing_prompt_version v WHERE v.environment=racing_prompt.environment AND v.scope_instance_id=racing_prompt.scope_instance_id AND v.prompt_id=racing_prompt.prompt_id AND v.purpose=#{purpose})</if> ORDER BY name,prompt_id LIMIT #{limit} OFFSET #{offset}</script>
        """)
    List<Map<String, Object>> listPrompt(Map<String, Object> parameters);

    @Select("""
        <script>SELECT count(*) FROM racing_prompt WHERE environment=#{environment} AND scope_instance_id=#{scope}<if test="name != ''"> AND strpos(name,#{name})&gt;0</if><if test="purpose != ''"> AND EXISTS(SELECT 1 FROM racing_prompt_version v WHERE v.environment=racing_prompt.environment AND v.scope_instance_id=racing_prompt.scope_instance_id AND v.prompt_id=racing_prompt.prompt_id AND v.purpose=#{purpose})</if></script>
        """)
    long countPrompt(Map<String, Object> parameters);

    @Select("""
        <script>SELECT environment,scope_instance_id,prompt_id::text AS id,version,created_by,created_at,purpose,body_type,body_text,variables_json::text,body_sha256,readable_scopes_json::text,authorization_revision,import_origin_json::text FROM racing_prompt_version WHERE environment=#{environment} AND scope_instance_id=#{scope} AND prompt_id=CAST(#{id} AS UUID) AND version=#{version}<if test="lock"> FOR UPDATE</if></script>
        """)
    Map<String, Object> getPromptVersion(Map<String, Object> parameters);

    @Select("""
        SELECT environment,scope_instance_id,prompt_id::text AS id,version,created_by,created_at,purpose,body_type,body_text,variables_json::text,body_sha256,readable_scopes_json::text,authorization_revision,import_origin_json::text FROM racing_prompt_version WHERE environment=#{environment} AND scope_instance_id=#{scope} AND prompt_id=CAST(#{id} AS UUID) ORDER BY version DESC LIMIT #{limit} OFFSET #{offset}
        """)
    List<Map<String, Object>> listPromptVersions(Map<String, Object> parameters);

    @Select("""
        SELECT count(*) FROM racing_prompt_version WHERE environment=#{environment} AND scope_instance_id=#{scope} AND prompt_id=CAST(#{id} AS UUID)
        """)
    long countPromptVersions(Map<String, Object> parameters);

    @Delete("""
        DELETE FROM racing_prompt_version WHERE environment=#{environment} AND scope_instance_id=#{scope} AND prompt_id=CAST(#{id} AS UUID) AND version=#{version}
        """)
    int deletePromptVersion(Map<String, Object> parameters);

    @Select("""
        <script>SELECT environment,scope_instance_id,set_id::text AS id,name,revision,created_by,created_at,modified_by,modified_at,last_action FROM racing_prompt_set WHERE environment=#{environment} AND scope_instance_id=#{scope} AND set_id=CAST(#{id} AS UUID)<if test="lock"> FOR UPDATE</if></script>
        """)
    Map<String, Object> getSet(Map<String, Object> parameters);

    @Select("""
        SELECT environment,scope_instance_id,set_id::text AS id,name,revision,created_by,created_at,modified_by,modified_at,last_action FROM racing_prompt_set WHERE environment=#{environment} AND scope_instance_id=#{scope} AND name=#{name}
        """)
    Map<String, Object> findSetByName(Map<String, Object> parameters);

    @Insert("""
        INSERT INTO racing_prompt_set(environment,scope_instance_id,set_id,name,revision,created_by,modified_by,last_action) VALUES(#{environment},#{scope},CAST(#{id} AS UUID),#{name},#{revision},#{principal},#{principal},#{action}) ON CONFLICT (environment,scope_instance_id,name) DO NOTHING
        """)
    int insertSet(Map<String, Object> parameters);

    @Update("""
        UPDATE racing_prompt_set SET name=#{name},revision=#{revision},modified_by=#{principal},modified_at=now(),last_action=#{action} WHERE environment=#{environment} AND scope_instance_id=#{scope} AND set_id=CAST(#{id} AS UUID) AND revision=#{expected}
        """)
    int updateSet(Map<String, Object> parameters);

    @Select("""
        <script>SELECT environment,scope_instance_id,set_id::text AS id,name,revision,created_by,created_at,modified_by,modified_at,last_action FROM racing_prompt_set WHERE environment=#{environment} AND scope_instance_id=#{scope}<if test="name != ''"> AND strpos(name,#{name})&gt;0</if> ORDER BY name,set_id LIMIT #{limit} OFFSET #{offset}</script>
        """)
    List<Map<String, Object>> listSet(Map<String, Object> parameters);

    @Select("""
        <script>SELECT count(*) FROM racing_prompt_set WHERE environment=#{environment} AND scope_instance_id=#{scope}<if test="name != ''"> AND strpos(name,#{name})&gt;0</if></script>
        """)
    long countSet(Map<String, Object> parameters);

    @Select("""
        <script>SELECT environment,scope_instance_id,set_id::text AS id,version,created_by,created_at,members_json::text,base_version_json::text,removed_fragment_names_json::text FROM racing_prompt_set_version WHERE environment=#{environment} AND scope_instance_id=#{scope} AND set_id=CAST(#{id} AS UUID) AND version=#{version}<if test="lock"> FOR UPDATE</if></script>
        """)
    Map<String, Object> getSetVersion(Map<String, Object> parameters);

    @Select("""
        SELECT environment,scope_instance_id,set_id::text AS id,version,created_by,created_at,members_json::text,base_version_json::text,removed_fragment_names_json::text FROM racing_prompt_set_version WHERE environment=#{environment} AND scope_instance_id=#{scope} AND set_id=CAST(#{id} AS UUID) ORDER BY version DESC LIMIT #{limit} OFFSET #{offset}
        """)
    List<Map<String, Object>> listSetVersions(Map<String, Object> parameters);

    @Select("""
        SELECT count(*) FROM racing_prompt_set_version WHERE environment=#{environment} AND scope_instance_id=#{scope} AND set_id=CAST(#{id} AS UUID)
        """)
    long countSetVersions(Map<String, Object> parameters);

    @Delete("""
        DELETE FROM racing_prompt_set_version WHERE environment=#{environment} AND scope_instance_id=#{scope} AND set_id=CAST(#{id} AS UUID) AND version=#{version}
        """)
    int deleteSetVersion(Map<String, Object> parameters);

    @Insert("""
        INSERT INTO racing_prompt_version(environment,scope_instance_id,prompt_id,version,purpose,body_type,body_text,variables_json,body_sha256,readable_scopes_json,authorization_revision,import_origin_json,created_by) VALUES(#{environment},#{scope},CAST(#{id} AS UUID),#{version},#{purpose},#{bodyType},#{body},CAST(#{variables} AS JSONB),#{sha},CAST(#{readable} AS JSONB),#{authRevision},CAST(#{origin} AS JSONB),#{principal})
        """)
    int insertPromptVersion(Map<String, Object> parameters);

    @Update("""
        UPDATE racing_prompt_version SET readable_scopes_json=CAST(#{readable} AS JSONB),authorization_revision=#{authRevision} WHERE environment=#{environment} AND scope_instance_id=#{scope} AND prompt_id=CAST(#{id} AS UUID) AND version=#{version} AND authorization_revision=#{expected}
        """)
    int updateAuthorization(Map<String, Object> parameters);

    @Insert("""
        INSERT INTO racing_prompt_set_version(environment,scope_instance_id,set_id,version,members_json,base_version_json,removed_fragment_names_json,created_by) VALUES(#{environment},#{scope},CAST(#{id} AS UUID),#{version},CAST(#{members} AS JSONB),CAST(#{base} AS JSONB),CAST(#{removed} AS JSONB),#{principal})
        """)
    int insertSetVersion(Map<String, Object> parameters);

    @Select("""
        <script>SELECT environment,scope_instance_id,business_key,revision,set_environment,set_scope_instance_id,set_id::text,set_version,modified_by,modified_at,last_action FROM racing_prompt_default_set WHERE environment=#{environment} AND scope_instance_id=#{scope} AND business_key=#{business}<if test="lock"> FOR UPDATE</if></script>
        """)
    Map<String, Object> getDefaultSet(Map<String, Object> parameters);

    @Insert("""
        INSERT INTO racing_prompt_default_set(environment,scope_instance_id,business_key,revision,set_environment,set_scope_instance_id,set_id,set_version,modified_by,last_action) VALUES(#{environment},#{scope},#{business},#{revision},#{targetEnvironment},#{targetScope},CAST(#{targetId} AS UUID),#{targetVersion},#{principal},#{action}) ON CONFLICT(environment,scope_instance_id,business_key) DO NOTHING
        """)
    int insertDefaultSet(Map<String, Object> parameters);

    @Update("""
        UPDATE racing_prompt_default_set SET revision=#{revision},set_environment=#{targetEnvironment},set_scope_instance_id=#{targetScope},set_id=CAST(#{targetId} AS UUID),set_version=#{targetVersion},modified_by=#{principal},modified_at=now(),last_action=#{action} WHERE environment=#{environment} AND scope_instance_id=#{scope} AND business_key=#{business} AND revision=#{expected}
        """)
    int updateDefaultSet(Map<String, Object> parameters);

    @Select("""
        <script>SELECT source_json::text FROM racing_prompt_root_selection WHERE environment=#{environment} AND scope_instance_id=#{scope} AND owner_namespace=#{namespace} AND owner_kind=#{kind} AND owner_scope_instance_id=#{ownerScope} AND root_id=#{rootId}<choose><when test="selectionId != null"> AND selection_id=CAST(#{selectionId} AS UUID)</when><otherwise> AND selection_key=#{selectionKey}</otherwise></choose></script>
        """)
    String getSelection(Map<String, Object> parameters);

    @Insert("""
        INSERT INTO racing_prompt_root_selection(selection_id,environment,scope_instance_id,owner_namespace,owner_kind,owner_scope_instance_id,root_id,selection_key,selection_kind,resource_environment,resource_scope_instance_id,prompt_id,prompt_version,set_id,set_version,source_json,created_by) VALUES(CAST(#{selectionId} AS UUID),#{environment},#{scope},#{namespace},#{kind},#{ownerScope},#{rootId},#{selectionKey},#{selectionKind},#{targetEnvironment},#{targetScope},CAST(#{promptId} AS UUID),#{promptVersion},CAST(#{setId} AS UUID),#{setVersion},CAST(#{source} AS JSONB),#{principal}) ON CONFLICT(environment,scope_instance_id,owner_namespace,owner_kind,owner_scope_instance_id,root_id,selection_key) DO NOTHING
        """)
    int insertSelection(Map<String, Object> parameters);

    @Select("""
        SELECT reference_id::text,environment,scope_instance_id,owner_environment,owner_scope_instance_id,owner_type,owner_id,owner_revision,prompt_id::text,prompt_version,set_id::text,set_version,created_at FROM racing_prompt_reference WHERE owner_environment=#{ownerEnvironment} AND owner_scope_instance_id=#{ownerScope} AND owner_service=#{principal} AND owner_type=#{ownerType} AND owner_id=#{ownerId} ORDER BY owner_revision,reference_id
        """)
    List<Map<String, Object>> ownerReferences(Map<String, Object> parameters);

    @Delete("""
        DELETE FROM racing_prompt_reference WHERE owner_environment=#{ownerEnvironment} AND owner_scope_instance_id=#{ownerScope} AND owner_service=#{principal} AND owner_type=#{ownerType} AND owner_id=#{ownerId}
        """)
    int deleteOwnerReferences(Map<String, Object> parameters);

    @Insert("""
        INSERT INTO racing_prompt_reference(reference_id,environment,scope_instance_id,owner_environment,owner_scope_instance_id,owner_service,owner_type,owner_id,owner_revision,prompt_id,prompt_version,set_id,set_version) VALUES(CAST(#{referenceId} AS UUID),#{environment},#{scope},#{ownerEnvironment},#{ownerScope},#{principal},#{ownerType},#{ownerId},#{ownerRevision},CAST(#{promptId} AS UUID),#{promptVersion},CAST(#{setId} AS UUID),#{setVersion})
        """)
    int insertReference(Map<String, Object> parameters);

    @Select("""
        <script>SELECT reference_id::text,environment,scope_instance_id,owner_environment,owner_scope_instance_id,owner_type,owner_id,owner_revision,prompt_id::text,prompt_version,set_id::text,set_version,created_at FROM racing_prompt_reference WHERE environment=#{environment} AND scope_instance_id=#{scope}<choose><when test="promptId != null"> AND prompt_id=CAST(#{promptId} AS UUID) AND prompt_version=#{promptVersion}</when><otherwise> AND set_id=CAST(#{setId} AS UUID) AND set_version=#{setVersion}</otherwise></choose> ORDER BY created_at,reference_id LIMIT #{limit} OFFSET #{offset}</script>
        """)
    List<Map<String, Object>> references(Map<String, Object> parameters);

    @Select("""
        <script>SELECT count(*) FROM racing_prompt_reference WHERE environment=#{environment} AND scope_instance_id=#{scope}<choose><when test="promptId != null"> AND prompt_id=CAST(#{promptId} AS UUID) AND prompt_version=#{promptVersion}</when><otherwise> AND set_id=CAST(#{setId} AS UUID) AND set_version=#{setVersion}</otherwise></choose></script>
        """)
    long referenceCount(Map<String, Object> parameters);

    @Delete("""
        DELETE FROM racing_prompt_reference WHERE owner_environment=#{environment} AND owner_scope_instance_id=#{scope} AND owner_type='PROMPT_SET_VERSION' AND owner_id=#{ownerId}
        """)
    int deleteSetMemberReferences(Map<String, Object> parameters);
}
