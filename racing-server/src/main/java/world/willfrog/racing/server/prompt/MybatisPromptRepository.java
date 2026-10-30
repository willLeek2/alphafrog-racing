package world.willfrog.racing.server.prompt;

import com.google.protobuf.Message;
import com.google.protobuf.ListValue;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;
import world.willfrog.racing.idl.*;

/**
 * 将不可变协议对象按既有八表结构保存；不把正文重新序列化或裁剪。
 */
@Repository
public class MybatisPromptRepository implements PromptRepository {

    private final PromptMapper mapper;

    public MybatisPromptRepository(PromptMapper mapper) {
        this.mapper = mapper;
    }

    private static Map<String, Object> scope(PromptScope s) {
        Map<String, Object> m = new HashMap<>();
        m.put("environment", s.getEnvironment());
        m.put("scope", s.getScopeInstanceId());
        return m;
    }

    private static Map<String, Object> key(PromptScope s, String id) {
        var m = scope(s);
        m.put("id", id);
        return m;
    }

    private static Map<String, Object> versionKey(PromptScope s, String id, long version) {
        var m = key(s, id);
        m.put("version", version);
        return m;
    }

    private static void page(Map<String, Object> m, int offset, int limit) {
        m.put("offset", offset);
        m.put("limit", limit);
    }

    private static String s(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : v.toString();
    }

    private static long n(Map<String, Object> m, String k) {
        return ((Number) m.get(k)).longValue();
    }

    private static Timestamp time(Object value) {
        Instant t = value instanceof java.sql.Timestamp v ? v.toInstant() : value instanceof OffsetDateTime v ? v.toInstant() : Instant.parse(value.toString());
        return Timestamp.newBuilder()
                        .setSeconds(t.getEpochSecond())
                        .setNanos(t.getNano())
                        .build();
    }

    private static PromptScope scope(Map<String, Object> m) {
        return PromptScope.newBuilder()
                          .setEnvironment(s(m, "environment"))
                          .setScopeInstanceId(s(m, "scope_instance_id"))
                          .build();
    }

    private static PromptCreationInfo creation(Map<String, Object> m) {
        return PromptCreationInfo.newBuilder()
                                 .setPrincipal(s(m, "created_by"))
                                 .setCreatedAt(time(m.get("created_at")))
                                 .build();
    }

    private static PromptModificationInfo modification(Map<String, Object> m) {
        return PromptModificationInfo.newBuilder()
                                     .setPrincipal(s(m, "modified_by"))
                                     .setModifiedAt(time(m.get("modified_at")))
                                     .setAction(s(m, "last_action"))
                                     .build();
    }

    private static String array(List<? extends Message> values) {
        return values.stream()
                     .map(PromptSupport::json)
                     .collect(Collectors.joining(",", "[", "]"));
    }

    private static String stringArray(List<String> values) {
        var array = ListValue.newBuilder();
        for (String value : values) {
            array.addValues(Value.newBuilder().setStringValue(value));
        }
        return PromptSupport.json(array.build());
    }

    private static void changed(int rows) {
        PromptSupport.require(rows == 1, "REVISION_CONFLICT", "内容已变化，请刷新后重新提交");
    }

    private static PromptRecord prompt(Map<String, Object> m) {
        if (m == null) {
            return null;
        }
        var b = PromptRecord.newBuilder()
                            .setLocator(PromptLocator.newBuilder().setScope(scope(m)).setPromptId(s(m, "id")))
                            .setName(s(m, "name"))
                            .setRevision(n(m, "revision"))
                            .setCreation(creation(m))
                            .setLastModification(modification(m));
        if (m.get("default_version") != null) {
            b.setDefaultVersion(n(m, "default_version"));
        }
        return b.build();
    }

    private static PromptSetRecord set(Map<String, Object> m) {
        if (m == null) {
            return null;
        }
        return PromptSetRecord.newBuilder()
                              .setLocator(PromptSetLocator.newBuilder().setScope(scope(m)).setPromptSetId(s(m, "id")))
                              .setName(s(m, "name"))
                              .setRevision(n(m, "revision"))
                              .setCreation(creation(m))
                              .setLastModification(modification(m))
                              .build();
    }

    private static PromptVersion version(Map<String, Object> m) {
        if (m == null) {
            return null;
        }
        var input = PromptSupport.parse("{\"variables\":" + s(m, "variables_json") + ",\"readableScopes\":" + s(m, "readable_scopes_json") + "}", PromptVersionInput.newBuilder())
                                 .build();
        var b = PromptVersion.newBuilder()
                             .setReference(PromptVersionRef.newBuilder().setScope(scope(m)).setPromptId(s(m, "id")).setVersion(n(m, "version")))
                             .setBody(s(m, "body_text"))
                             .setBodyType("FIXED".equals(s(m, "body_type")) ? PromptBodyType.PROMPT_BODY_FIXED : PromptBodyType.PROMPT_BODY_TEMPLATE)
                             .addAllVariables(input.getVariablesList())
                             .setPurpose(s(m, "purpose"))
                             .setAuthorization(PromptVersionAuthorization.newBuilder().setRevision(n(m, "authorization_revision")).addAllReadableScopes(input.getReadableScopesList()))
                             .setSha256(s(m, "body_sha256"))
                             .setUtf8Length(s(m, "body_text").getBytes(StandardCharsets.UTF_8).length)
                             .setCreation(creation(m));
        if (m.get("import_origin_json") != null) {
            b.setImportOrigin(PromptSupport.parse(s(m, "import_origin_json"), PromptImportOrigin.newBuilder()));
        }
        return b.build();
    }

    private static PromptSetVersion setVersion(Map<String, Object> m) {
        if (m == null) {
            return null;
        }
        var version = PromptSupport.parse("{\"members\":" + s(m, "members_json")
                + ",\"removedFragmentNames\":" + s(m, "removed_fragment_names_json") + "}", PromptSetVersion.newBuilder())
                                   .setReference(PromptSetVersionRef.newBuilder().setScope(scope(m)).setPromptSetId(s(m, "id")).setVersion(n(m, "version")))
                                   .setCreation(creation(m));
        if (m.get("base_version_json") != null) {
            version.setBaseVersion(PromptSupport.parse(s(m, "base_version_json"), PromptSetVersionRef.newBuilder()));
        }
        return version.build();
    }

    private static Map<String, Object> record(PromptRecord v) {
        var m = key(v.getLocator().getScope(), v.getLocator().getPromptId());
        m.put("name", v.getName());
        m.put("revision", v.getRevision());
        m.put("principal", v.getLastModification().getPrincipal());
        m.put("action", v.getLastModification().getAction());
        m.put("defaultVersion", v.hasDefaultVersion() ? v.getDefaultVersion() : null);
        return m;
    }

    private static Map<String, Object> record(PromptSetRecord v) {
        var m = key(v.getLocator().getScope(), v.getLocator().getPromptSetId());
        m.put("name", v.getName());
        m.put("revision", v.getRevision());
        m.put("principal", v.getLastModification().getPrincipal());
        m.put("action", v.getLastModification().getAction());
        return m;
    }

    public PromptRecord prompt(PromptLocator k, boolean lock) {
        var m = key(k.getScope(), k.getPromptId());
        m.put("lock", lock);
        return prompt(mapper.getPrompt(m));
    }

    public PromptRecord promptByName(PromptScope s, String name) {
        var m = scope(s);
        m.put("name", name);
        return prompt(mapper.findPromptByName(m));
    }

    public boolean insertPrompt(PromptRecord v) {
        return mapper.insertPrompt(record(v)) == 1;
    }

    public void updatePrompt(PromptRecord v, long previous) {
        var m = record(v);
        m.put("expected", previous);
        changed(mapper.updatePrompt(m));
    }

    public PromptVersion version(PromptVersionRef k, boolean lock) {
        var m = versionKey(k.getScope(), k.getPromptId(), k.getVersion());
        m.put("lock", lock);
        return version(mapper.getPromptVersion(m));
    }

    public void insertVersion(PromptVersion v) {
        var k = v.getReference();
        var m = versionKey(k.getScope(), k.getPromptId(), k.getVersion());
        m.put("body", v.getBody());
        m.put("purpose", v.getPurpose());
        m.put("bodyType", v.getBodyType() == PromptBodyType.PROMPT_BODY_FIXED ? "FIXED" : "TEMPLATE");
        m.put("variables", array(v.getVariablesList()));
        m.put("readable", array(v.getAuthorization().getReadableScopesList()));
        m.put("authRevision", v.getAuthorization().getRevision());
        m.put("origin", v.hasImportOrigin() ? PromptSupport.json(v.getImportOrigin()) : null);
        m.put("sha", v.getSha256());
        m.put("principal", v.getCreation().getPrincipal());
        mapper.insertPromptVersion(m);
    }

    public void updateAuthorization(PromptVersion v, long previous) {
        var k = v.getReference();
        var m = versionKey(k.getScope(), k.getPromptId(), k.getVersion());
        m.put("readable", array(v.getAuthorization().getReadableScopesList()));
        m.put("authRevision", v.getAuthorization().getRevision());
        m.put("expected", previous);
        changed(mapper.updateAuthorization(m));
    }

    public List<PromptRecord> prompts(PromptScope s, String name, String purpose, int offset, int limit) {
        var m = scope(s);
        m.put("name", name);
        m.put("purpose", purpose);
        page(m, offset, limit);
        return mapper.listPrompt(m)
                     .stream()
                     .map(MybatisPromptRepository::prompt)
                     .toList();
    }

    public long promptCount(PromptScope s, String name, String purpose) {
        var m = scope(s);
        m.put("name", name);
        m.put("purpose", purpose);
        return mapper.countPrompt(m);
    }

    public List<PromptVersion> versions(PromptLocator k, int offset, int limit) {
        var m = key(k.getScope(), k.getPromptId());
        page(m, offset, limit);
        return mapper.listPromptVersions(m)
                     .stream()
                     .map(MybatisPromptRepository::version)
                     .toList();
    }

    public long versionCount(PromptLocator k) {
        return mapper.countPromptVersions(key(k.getScope(), k.getPromptId()));
    }

    public PromptSetRecord promptSet(PromptSetLocator k, boolean lock) {
        var m = key(k.getScope(), k.getPromptSetId());
        m.put("lock", lock);
        return set(mapper.getSet(m));
    }

    public boolean insertSet(PromptSetRecord v) {
        return mapper.insertSet(record(v)) == 1;
    }

    public void updateSet(PromptSetRecord v, long previous) {
        var m = record(v);
        m.put("expected", previous);
        changed(mapper.updateSet(m));
    }

    public PromptSetVersion setVersion(PromptSetVersionRef k, boolean lock) {
        var m = versionKey(k.getScope(), k.getPromptSetId(), k.getVersion());
        m.put("lock", lock);
        return setVersion(mapper.getSetVersion(m));
    }

    public void insertSetVersion(PromptSetVersion v) {
        var k = v.getReference();
        var m = versionKey(k.getScope(), k.getPromptSetId(), k.getVersion());
        m.put("members", array(v.getMembersList()));
        m.put("base", v.hasBaseVersion() ? PromptSupport.json(v.getBaseVersion()) : null);
        m.put("removed", stringArray(v.getRemovedFragmentNamesList()));
        m.put("principal", v.getCreation().getPrincipal());
        mapper.insertSetVersion(m);
    }

    public List<PromptSetRecord> sets(PromptScope s, String name, int offset, int limit) {
        var m = scope(s);
        m.put("name", name);
        page(m, offset, limit);
        return mapper.listSet(m)
                     .stream()
                     .map(MybatisPromptRepository::set)
                     .toList();
    }

    public long setCount(PromptScope s, String name) {
        var m = scope(s);
        m.put("name", name);
        return mapper.countSet(m);
    }

    public List<PromptSetVersion> setVersions(PromptSetLocator k, int offset, int limit) {
        var m = key(k.getScope(), k.getPromptSetId());
        page(m, offset, limit);
        return mapper.listSetVersions(m)
                     .stream()
                     .map(MybatisPromptRepository::setVersion)
                     .toList();
    }

    public long setVersionCount(PromptSetLocator k) {
        return mapper.countSetVersions(key(k.getScope(), k.getPromptSetId()));
    }

    private static Map<String, Object> defaultKey(PromptScope s, String business) {
        var m = scope(s);
        m.put("business", business);
        return m;
    }

    public PromptSetDefault defaultSet(PromptScope s, String business, boolean lock) {
        var m = defaultKey(s, business);
        m.put("lock", lock);
        var row = mapper.getDefaultSet(m);
        if (row == null) {
            return null;
        }
        return PromptSetDefault.newBuilder()
                               .setScope(scope(row))
                               .setBusinessName(s(row, "business_key"))
                               .setRevision(n(row, "revision"))
                               .setVersion(PromptSetVersionRef.newBuilder().setScope(PromptScope.newBuilder().setEnvironment(s(row, "set_environment")).setScopeInstanceId(s(row, "set_scope_instance_id"))).setPromptSetId(s(row, "set_id")).setVersion(n(row, "set_version")))
                               .setLastModification(modification(row))
                               .build();
    }

    private static Map<String, Object> defaultRow(PromptSetDefault v) {
        var m = defaultKey(v.getScope(), v.getBusinessName());
        m.put("revision", v.getRevision());
        m.put("targetEnvironment", v.getVersion().getScope().getEnvironment());
        m.put("targetScope", v.getVersion().getScope().getScopeInstanceId());
        m.put("targetId", v.getVersion().getPromptSetId());
        m.put("targetVersion", v.getVersion().getVersion());
        m.put("principal", v.getLastModification().getPrincipal());
        m.put("action", v.getLastModification().getAction());
        return m;
    }

    public boolean insertDefaultSet(PromptSetDefault v) {
        return mapper.insertDefaultSet(defaultRow(v)) == 1;
    }

    public void updateDefaultSet(PromptSetDefault v, long previous) {
        var m = defaultRow(v);
        m.put("expected", previous);
        changed(mapper.updateDefaultSet(m));
    }

    private static Map<String, Object> root(RuntimeCallContext c) {
        var m = new HashMap<String, Object>();
        m.put("environment", c.getTarget().getEnvironment());
        m.put("scope", c.getTarget().getTargetScopeInstanceId());
        m.put("namespace", c.getRoot().getOwnerNamespace());
        m.put("kind", c.getRoot().getOwnerKind());
        m.put("ownerScope", c.getRoot().getOwnerScopeInstanceId());
        m.put("rootId", c.getRoot().getRootId());
        return m;
    }

    public PromptSelection selection(RuntimeCallContext c, String id, String key) {
        var m = root(c);
        m.put("selectionId", id);
        m.put("selectionKey", key);
        String value = mapper.getSelection(m);
        return value == null ? null : PromptSupport.parse(value, PromptSelection.newBuilder())
                                                   .build();
    }

    private static void target(Map<String, Object> m, PromptTargetRef t) {
        m.put("targetEnvironment", PromptSupport.scope(t).getEnvironment());
        m.put("targetScope", PromptSupport.scope(t).getScopeInstanceId());
        m.put("promptId", t.hasPromptVersion() ? t.getPromptVersion()
                                                  .getPromptId() : null);
        m.put("promptVersion", t.hasPromptVersion() ? t.getPromptVersion()
                                                       .getVersion() : null);
        m.put("setId", t.hasPromptSetVersion() ? t.getPromptSetVersion()
                                                  .getPromptSetId() : null);
        m.put("setVersion", t.hasPromptSetVersion() ? t.getPromptSetVersion()
                                                       .getVersion() : null);
    }

    public boolean insertSelection(PromptSelection v, String principal) {
        var m = root(RuntimeCallContext.newBuilder().setRoot(v.getRoot()).setTarget(v.getTarget()).build());
        target(m, v.getSelectedTarget());
        m.put("selectionId", v.getSelectionId());
        m.put("selectionKey", v.getSelectionKey());
        m.put("selectionKind", v.getSelectedTarget()
                                .hasPromptVersion() ? "PROMPT" : "SET");
        m.put("source", PromptSupport.json(v));
        m.put("principal", principal);
        return mapper.insertSelection(m) == 1;
    }

    private static Map<String, Object> owner(PromptReferenceOwner o, String principal) {
        var m = new HashMap<String, Object>();
        m.put("ownerEnvironment", o.getScope().getEnvironment());
        m.put("ownerScope", o.getScope().getScopeInstanceId());
        m.put("ownerType", o.getOwnerType());
        m.put("ownerId", o.getOwnerId());
        m.put("ownerRevision", o.getRevision());
        m.put("principal", principal);
        return m;
    }

    private static PromptReference reference(Map<String, Object> m) {
        PromptTargetRef target;
        if (m.get("prompt_id") != null) {
            target = PromptSupport.target(PromptVersionRef.newBuilder().setScope(scope(m)).setPromptId(s(m, "prompt_id")).setVersion(n(m, "prompt_version")).build());
        } else {
            target = PromptSupport.target(PromptSetVersionRef.newBuilder().setScope(scope(m)).setPromptSetId(s(m, "set_id")).setVersion(n(m, "set_version")).build());
        }
        return PromptReference.newBuilder()
                              .setReferenceId(s(m, "reference_id"))
                              .setTarget(target)
                              .setOwner(PromptReferenceOwner.newBuilder().setScope(PromptScope.newBuilder().setEnvironment(s(m, "owner_environment")).setScopeInstanceId(s(m, "owner_scope_instance_id"))).setOwnerType(s(m, "owner_type")).setOwnerId(s(m, "owner_id")).setRevision(n(m, "owner_revision")))
                              .setRegisteredAt(time(m.get("created_at")))
                              .build();
    }

    public List<PromptReference> ownerReferences(PromptReferenceOwner o, String principal) {
        return mapper.ownerReferences(owner(o, principal))
                     .stream()
                     .map(MybatisPromptRepository::reference)
                     .toList();
    }

    public void deleteOwnerReferences(PromptReferenceOwner o, String principal) {
        mapper.deleteOwnerReferences(owner(o, principal));
    }

    public void insertReference(PromptReference v, String principal) {
        var m = owner(v.getOwner(), principal);
        target(m, v.getTarget());
        m.put("environment", PromptSupport.scope(v.getTarget()).getEnvironment());
        m.put("scope", PromptSupport.scope(v.getTarget()).getScopeInstanceId());
        m.put("referenceId", v.getReferenceId());
        mapper.insertReference(m);
    }

    private static Map<String, Object> target(PromptTargetRef t) {
        var m = scope(PromptSupport.scope(t));
        target(m, t);
        return m;
    }

    public List<PromptReference> references(PromptTargetRef t, int offset, int limit) {
        var m = target(t);
        page(m, offset, limit);
        return mapper.references(m)
                     .stream()
                     .map(MybatisPromptRepository::reference)
                     .toList();
    }

    public long referenceCount(PromptTargetRef t) {
        return mapper.referenceCount(target(t));
    }

    public void deleteVersion(PromptVersionRef t) {
        changed(mapper.deletePromptVersion(versionKey(t.getScope(), t.getPromptId(), t.getVersion())));
    }

    public void deleteSetVersion(PromptSetVersionRef t) {
        changed(mapper.deleteSetVersion(versionKey(t.getScope(), t.getPromptSetId(), t.getVersion())));
    }

    public void deleteSetMemberReferences(PromptSetVersionRef t) {
        var m = scope(t.getScope());
        m.put("ownerId", t.getPromptSetId() + ":" + t.getVersion());
        mapper.deleteSetMemberReferences(m);
    }
}
