package world.willfrog.racing.server.input;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.JsonFormat;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import world.willfrog.racing.idl.*;
import world.willfrog.racing.server.prompt.PromptBusinessException;
import world.willfrog.racing.server.prompt.PromptReferenceService;
import world.willfrog.racing.server.prompt.PromptSourceService;
import world.willfrog.racing.server.security.AuthorizedCall;

/** 保存确认只在事务提交后返回；输入正文和明确版本引用共同成功或共同回滚。 */
@Service
public class ModelInputService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonFormat.Printer PROTO_JSON = JsonFormat.printer().omittingInsignificantWhitespace();
    private final ModelInputRepository repository;
    private final PromptReferenceService references;
    private final PromptSourceService sources;
    private final ModelInputContentValidator validator = new ModelInputContentValidator();

    public ModelInputService(ModelInputRepository repository, PromptReferenceService references,
            PromptSourceService sources) {
        this.repository = repository;
        this.references = references;
        this.sources = sources;
    }

    @Transactional
    public ModelInputReceipt store(AuthorizedCall call, StoreModelInputRequest request) {
        requireContext(call, request == null ? null : request.getContext());
        ModelInputContentValidator.Validated valid = validator.validate(request);
        RuntimeCallContext context = call.context();
        RootIdentity root = context.getRoot();
        if (context.getCurrentRunId().isBlank()) throw failure("INVALID_ARGUMENT", "模型输入缺少当前运行编号");
        String logical = uuid(request.getLogicalCallId());
        String attempt = uuid(request.getAttemptUuid());
        ModelInputRow candidate = new ModelInputRow(UUID.randomUUID().toString(), call.principal(),
                context.getTarget().getEnvironment(), context.getTarget().getTargetScopeInstanceId(),
                root.getOwnerNamespace(), root.getOwnerKind(), root.getOwnerScopeInstanceId(), root.getRootId(),
                context.getCurrentRunId(), logical, attempt, request.getEndpointName(), request.getInputJson(),
                valid.utf8Length(), valid.sha256(), protoJson(context), sourceJson(request.getSourcesList()), null);
        ModelInputRow stored = repository.insertAndLock(candidate);
        if (stored == null) throw failure("INPUT_STORE_UNCONFIRMED", "未能确认已保存的模型输入");
        ModelInputRecord record = record(stored);
        if (!sameInput(candidate, request, record)) {
            throw failure("CONFLICT", "同一服务尝试编号已经保存不同的输入对象");
        }
        Set<PromptTargetRef> targets = new LinkedHashSet<>();
        Set<PromptInstructionSource> instructions = new LinkedHashSet<>();
        request.getSourcesList().forEach(binding -> instructions.addAll(binding.getInstructionSourcesList()));
        // 同一次保存批量核验，复用已读取的选择、集合和版本；重发仍重新核对当前授权。
        sources.validateInstructionSources(call, List.copyOf(instructions));
        for (PromptInstructionSource source : instructions) {
            targets.add(PromptTargetRef.newBuilder().setPromptVersion(source.getVersion()).build());
            if (source.hasPromptSetVersion()) {
                targets.add(PromptTargetRef.newBuilder().setPromptSetVersion(source.getPromptSetVersion()).build());
            }
        }
        references.replaceForOwner(call, PromptReferenceReplacement.newBuilder()
                .setOwner(PromptReferenceOwner.newBuilder()
                        .setScope(PromptScope.newBuilder().setEnvironment(stored.environment())
                                .setScopeInstanceId(stored.scopeInstanceId()))
                        .setOwnerType("MODEL_INPUT").setOwnerId(stored.recordId()).setRevision(0))
                .addAllTargets(targets).build());
        return record.getReceipt();
    }

    /** 公共远程入口必须先执行GetModelInput独立授权；当前该权限尚未开放。 */
    @Transactional(readOnly = true)
    public ModelInputRecord get(AuthorizedCall call, GetModelInputRequest request) {
        requireContext(call, request == null ? null : request.getContext());
        ModelInputRow row = switch (request.getLocatorCase()) {
            case MODEL_INPUT_ID -> repository.findById(uuid(request.getModelInputId()));
            case ATTEMPT -> {
                if (request.getAttempt().getServicePrincipal().isBlank()) {
                    throw failure("INVALID_ARGUMENT", "历史查询缺少保存服务身份");
                }
                yield repository.findAttempt(request.getAttempt().getServicePrincipal(),
                        uuid(request.getAttempt().getAttemptUuid()));
            }
            default -> throw failure("INVALID_ARGUMENT", "历史查询需要记录编号或完整尝试定位");
        };
        if (row == null) throw failure("NOT_FOUND", "模型输入不存在");
        call.requireReadableSource(row.environment(), row.scopeInstanceId());
        return record(row);
    }

    private static boolean sameInput(ModelInputRow candidate, StoreModelInputRequest request,
            ModelInputRecord stored) {
        return candidate.servicePrincipal().equals(stored.getServicePrincipal())
                && candidate.logicalCallId().equals(stored.getReceipt().getLogicalCallId())
                && candidate.attemptId().equals(stored.getReceipt().getAttemptUuid())
                && candidate.utf8Length() == stored.getReceipt().getUtf8Length()
                && candidate.bodySha256().equals(stored.getReceipt().getSha256())
                && request.getContext().equals(stored.getContext())
                && request.getInputJson().equals(stored.getInputJson())
                && request.getEndpointName().equals(stored.getEndpointName())
                && request.getSourcesList().equals(stored.getSourcesList());
    }

    private static ModelInputRecord record(ModelInputRow row) {
        try {
            RuntimeCallContext.Builder context = RuntimeCallContext.newBuilder();
            JsonFormat.parser().merge(row.contextJson(), context);
            JsonNode tree = JSON.readTree(row.sourcesJson());
            if (!tree.isArray() || row.createdAt() == null) throw new IllegalArgumentException();
            Instant at = row.createdAt().toInstant();
            ModelInputRecord.Builder result = ModelInputRecord.newBuilder()
                    .setReceipt(ModelInputReceipt.newBuilder().setModelInputId(row.recordId())
                            .setLogicalCallId(row.logicalCallId()).setAttemptUuid(row.attemptId())
                            .setUtf8Length(row.utf8Length()).setSha256(row.bodySha256())
                            .setStoredAt(Timestamp.newBuilder().setSeconds(at.getEpochSecond()).setNanos(at.getNano())))
                    .setContext(context).setServicePrincipal(row.servicePrincipal())
                    .setInputJson(row.bodyJson()).setEndpointName(row.endpointName());
            for (JsonNode value : tree) {
                ModelInputSourceBinding.Builder binding = ModelInputSourceBinding.newBuilder();
                JsonFormat.parser().merge(value.toString(), binding);
                result.addSources(binding);
            }
            return result.build();
        } catch (Exception invalid) {
            // JSON解析异常可能携带原正文，不能把原异常传给远程调用或日志。
            throw failure("INPUT_STORE_UNCONFIRMED", "已保存的模型输入无法完整还原");
        }
    }

    private static String sourceJson(List<ModelInputSourceBinding> bindings) {
        ArrayNode array = JSON.createArrayNode();
        try {
            for (ModelInputSourceBinding binding : bindings) array.add(JSON.readTree(PROTO_JSON.print(binding)));
            return array.toString();
        } catch (Exception invalid) {
            throw failure("INVALID_ARGUMENT", "模型输入来源无法序列化");
        }
    }

    private static String protoJson(RuntimeCallContext context) {
        try { return PROTO_JSON.print(context); }
        catch (Exception invalid) { throw failure("INVALID_ARGUMENT", "模型输入上下文无法序列化"); }
    }

    private static void requireContext(AuthorizedCall call, RuntimeCallContext context) {
        if (call == null || context == null || !call.context().equals(context)) {
            throw failure("FORBIDDEN", "模型输入上下文与本次授权不一致");
        }
    }

    private static String uuid(String value) {
        try {
            String canonical = UUID.fromString(value).toString();
            if (canonical.equalsIgnoreCase(value)) return canonical;
        } catch (IllegalArgumentException ignored) { }
        throw failure("INVALID_ARGUMENT", "模型输入定位需要完整UUID");
    }

    private static PromptBusinessException failure(String code, String message) {
        return new PromptBusinessException(RacingError.newBuilder().setCode(code).setMessage(message).build());
    }
}
