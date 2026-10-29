package world.willfrog.racing.server.input;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import world.willfrog.racing.idl.FieldViolation;
import world.willfrog.racing.idl.ModelInputContentKind;
import world.willfrog.racing.idl.ModelInputSourceBinding;
import world.willfrog.racing.idl.PromptInstructionSource;
import world.willfrog.racing.idl.PromptScope;
import world.willfrog.racing.idl.RacingError;
import world.willfrog.racing.idl.StoreModelInputRequest;
import world.willfrog.racing.idl.ValueSource;
import world.willfrog.racing.idl.ValueSourceKind;
import world.willfrog.racing.server.prompt.PromptBusinessException;

/** 独立核对原始输入与实际来源位置；不代替版本查询、授权及引用保存事务。 */
public final class ModelInputContentValidator {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public Validated validate(StoreModelInputRequest request) {
        if (request == null) throw invalid("request", "REQUIRED", "需要提供完整模型输入请求");
        uuid(request.getLogicalCallId(), "logical_call_id");
        uuid(request.getAttemptUuid(), "attempt_uuid");
        required(request.getEndpointName(), "endpoint_name");
        storedText(request.getEndpointName(), "endpoint_name");
        if (!request.hasContext() || !request.getContext().hasRoot() || !request.getContext().hasTarget()) {
            throw invalid("context", "REQUIRED", "模型输入需要根归属和目标上下文");
        }
        // context和sources进入JSONB；这里只查可保存字符，实际身份与权限由公共入口核对。
        storedMessage(request.getContext(), "context");
        byte[] bytes = storedText(request.getInputJson(), "input_json");
        String digest = sha256(bytes);
        if (request.getUtf8Length() != bytes.length) {
            throw invalid("utf8_length", "LENGTH_MISMATCH", "输入长度与原始UTF-8字节不一致");
        }
        if (!digest.equalsIgnoreCase(request.getSha256())) {
            throw invalid("sha256", "DIGEST_MISMATCH", "输入摘要与原始UTF-8字节不一致");
        }
        JsonNode body = inputObject(request.getInputJson());
        Map<String, Position> positions = positions(body);
        Map<String, ModelInputSourceBinding> bound = new LinkedHashMap<>();
        for (int i = 0; i < request.getSourcesCount(); i++) {
            ModelInputSourceBinding binding = request.getSources(i);
            String field = "sources[" + i + "]";
            storedMessage(binding, field);
            String pointer = pointer(binding, field);
            Position position = positions.get(pointer);
            if (position == null || body.at(pointer).isMissingNode() || body.at(pointer).isNull()) {
                throw invalid(field, "INVALID_LOCATION", "来源位置已不存在或不属于可登记位置");
            }
            if (bound.putIfAbsent(pointer, binding) != null) {
                throw invalid(field, "DUPLICATE_LOCATION", "同一输入位置不能重复登记来源");
            }
            validateLocation(binding, position, field);
            validateKind(binding, position, field);
            for (int j = 0; j < binding.getInstructionSourcesCount(); j++) {
                validateInstruction(binding.getInstructionSources(j), field + ".instruction_sources[" + j + "]");
            }
        }
        for (Map.Entry<String, Position> entry : positions.entrySet()) {
            if (bound.containsKey(entry.getKey())) continue;
            Position position = entry.getValue();
            ModelInputSourceBinding whole = position.coveredBy() == null ? null : bound.get(position.coveredBy());
            if (whole == null || whole.getContentKind() != ModelInputContentKind.MODEL_INPUT_APPLICATION_INSTRUCTION) {
                throw missing(position.safeField(), "模型输入缺少来源或运行数据归类");
            }
        }
        return new Validated(request, bytes.length, digest);
    }

    private static Map<String, Position> positions(JsonNode body) {
        Map<String, Position> out = new LinkedHashMap<>();
        String prefix = body.has("messages") ? "/messages" : "/input/messages";
        JsonNode messages = body.at(prefix);
        if (!body.has("messages") && !body.has("input") && body.has("query")) {
            text(body.get("query"), "input_json.query");
            if (body.has("tools") || body.has("response_format")) {
                throw invalid("input_json", "INVALID_QUERY_STRUCTURE", "搜索输入不能混用消息协议结构");
            }
            // 单查询沿用消息位置联合字段，序号零指向原始query字段。
            out.put("/query", new Position(ModelInputSourceBinding.LocationCase.MESSAGE, 0, "",
                    false, null, "input_json.query"));
            JsonNode schema = body.get("outputSchema");
            if (schema != null && !schema.isNull()) {
                structure(schema, "/outputSchema", ModelInputSourceBinding.LocationCase.OUTPUT_STRUCTURE,
                        "", "input_json.outputSchema", out);
            }
            return out;
        }
        if (!messages.isArray() || messages.isEmpty()) {
            throw invalid("input_json.messages", "INVALID_MESSAGES", "模型输入需要非空消息列表");
        }
        for (int i = 0; i < messages.size(); i++) {
            JsonNode message = messages.get(i);
            String role = message.path("role").asText();
            boolean tool = "tool".equals(role);
            String field = "input_json.messages[" + i + "]";
            String callId = tool ? text(message.get("tool_call_id"), field + ".tool_call_id") : "";
            out.put(prefix + "/" + i, new Position(tool ? ModelInputSourceBinding.LocationCase.TOOL_RESULT
                    : ModelInputSourceBinding.LocationCase.MESSAGE, i, callId,
                    "system".equals(role) || "developer".equals(role), null, field));
        }
        JsonNode tools = body.get("tools");
        if (tools != null && !tools.isNull()) {
            if (!tools.isArray()) throw invalid("input_json.tools", "INVALID_TOOLS", "工具定义需要是列表");
            for (int i = 0; i < tools.size(); i++) {
                JsonNode tool = tools.get(i);
                String field = "input_json.tools[" + i + "]";
                JsonNode name = tool.has("function") ? tool.path("function").get("name") : tool.get("name");
                structure(tool, "/tools/" + i, ModelInputSourceBinding.LocationCase.TOOL_DEFINITION,
                        text(name, field + ".name"), field, out);
            }
        }
        JsonNode output = body.get("response_format");
        if (output != null && !output.isNull()) {
            structure(output, "/response_format", ModelInputSourceBinding.LocationCase.OUTPUT_STRUCTURE,
                    "", "input_json.response_format", out);
        }
        return out;
    }

    private static void structure(JsonNode node, String path, ModelInputSourceBinding.LocationCase kind,
                                  String name, String safeField, Map<String, Position> out) {
        out.put(path, new Position(kind, -1, name, false, null, safeField));
        Set<String> descriptions = new LinkedHashSet<>();
        descriptions(node, path, descriptions);
        for (String description : descriptions) {
            out.put(description, new Position(kind, -1, name, true, path, safeField));
        }
    }

    private static void descriptions(JsonNode value, String path, Set<String> out) {
        if (value.isObject()) {
            value.fields().forEachRemaining(field -> {
                String child = path + "/" + field.getKey().replace("~", "~0").replace("/", "~1");
                if ((field.getKey().equals("description") || field.getKey().equals("title")
                        || field.getKey().equals("$comment")) && field.getValue().isTextual()
                        && !field.getValue().textValue().isBlank()) out.add(child);
                descriptions(field.getValue(), child, out);
            });
        } else if (value.isArray()) {
            for (int i = 0; i < value.size(); i++) descriptions(value.get(i), path + "/" + i, out);
        }
    }

    private static String pointer(ModelInputSourceBinding binding, String field) {
        String pointer = switch (binding.getLocationCase()) {
            case MESSAGE -> binding.getMessage().getFieldPath();
            case TOOL_DEFINITION -> binding.getToolDefinition().getFieldPath();
            case OUTPUT_STRUCTURE -> binding.getOutputStructure().getFieldPath();
            case TOOL_RESULT -> binding.getToolResult().getFieldPath();
            default -> throw invalid(field + ".location", "REQUIRED", "来源需要明确位置类型");
        };
        if (!pointer.startsWith("/")) throw invalid(field + ".field_path", "INVALID_LOCATION", "来源需要有效JSON Pointer位置");
        for (int i = 0; i < pointer.length(); i++) {
            if (pointer.charAt(i) == '~' && (++i >= pointer.length()
                    || (pointer.charAt(i) != '0' && pointer.charAt(i) != '1'))) {
                throw invalid(field + ".field_path", "INVALID_LOCATION", "来源位置包含无效转义");
            }
        }
        return pointer;
    }

    private static void validateLocation(ModelInputSourceBinding binding, Position position, String field) {
        if (binding.getLocationCase() != position.kind()) {
            throw invalid(field + ".location", "LOCATION_MISMATCH", "来源类型与实际消息或结构不一致");
        }
        switch (binding.getLocationCase()) {
            case MESSAGE -> {
                if (binding.getMessage().getMessageIndex() != position.messageIndex()) {
                    throw invalid(field + ".message.message_index", "LOCATION_MISMATCH", "消息序号与实际位置不一致");
                }
            }
            case TOOL_RESULT -> {
                var tool = binding.getToolResult();
                if (!tool.getToolCallId().equals(position.name())
                        || tool.hasMessageIndex() && tool.getMessageIndex() != position.messageIndex()) {
                    throw invalid(field + ".tool_result", "LOCATION_MISMATCH", "工具结果调用编号或序号与实际消息不一致");
                }
            }
            case TOOL_DEFINITION -> {
                if (!binding.getToolDefinition().getToolName().equals(position.name())) {
                    throw invalid(field + ".tool_definition.tool_name", "LOCATION_MISMATCH", "来源工具名称与实际定义不一致");
                }
            }
            default -> { }
        }
    }

    private static void validateKind(ModelInputSourceBinding binding, Position position, String field) {
        switch (binding.getContentKind()) {
            case MODEL_INPUT_APPLICATION_INSTRUCTION -> {
                if (binding.getInstructionSourcesCount() == 0) throw missing(field + ".instruction_sources", "应用指令需要明确正文版本来源");
            }
            case MODEL_INPUT_RUNTIME_DATA -> {
                if (binding.getInstructionSourcesCount() != 0) throw invalid(field + ".instruction_sources", "KIND_MISMATCH", "运行数据不能携带应用指令版本");
                if (position.instruction()) throw missing(field, "系统消息或结构说明需要明确指令版本");
            }
            default -> throw invalid(field + ".content_kind", "INVALID_KIND", "来源需要明确指令或运行数据类型");
        }
    }

    private static void validateInstruction(PromptInstructionSource source, String field) {
        if (!source.hasVersion()) throw missing(field + ".version", "指令需要明确正文版本");
        var version = source.getVersion();
        if (!version.hasScope()) throw missing(field + ".version.scope", "正文版本需要资源范围");
        scope(version.getScope(), field + ".version.scope");
        required(version.getPromptId(), field + ".version.prompt_id");
        positive(version.getVersion(), field + ".version.version");
        optional(source.getSelectionId(), field + ".selection_id");
        optional(source.getSelectionKey(), field + ".selection_key");
        optional(source.getFragmentName(), field + ".fragment_name");
        if (source.hasPromptSetVersion()) {
            var set = source.getPromptSetVersion();
            if (!set.hasScope()) throw missing(field + ".prompt_set_version.scope", "集合版本需要资源范围");
            scope(set.getScope(), field + ".prompt_set_version.scope");
            required(set.getPromptSetId(), field + ".prompt_set_version.prompt_set_id");
            positive(set.getVersion(), field + ".prompt_set_version.version");
        }
        if (source.hasExperiment() && source.hasOrdinaryDefaultSource()) {
            throw invalid(field, "SOURCE_CONFLICT", "普通默认和实验来源不能同时填写");
        }
        if (source.hasOrdinaryDefaultSource()) {
            valueSource(source.getOrdinaryDefaultSource(), field + ".ordinary_default_source");
            if (source.getOrdinaryDefaultSource().getKind() != ValueSourceKind.ORDINARY_DEFAULT) {
                throw invalid(field + ".ordinary_default_source.kind", "SOURCE_CONFLICT", "非实验来源只能是普通默认");
            }
        }
        if (source.hasExperiment()) {
            var experiment = source.getExperiment();
            required(experiment.getExperimentId(), field + ".experiment.experiment_id");
            if (!experiment.hasValueSource()) throw missing(field + ".experiment.value_source", "实验指令需要实际取值来源");
            valueSource(experiment.getValueSource(), field + ".experiment.value_source");
            required(experiment.getValueSource().getConfigurationScopeInstanceId(),
                    field + ".experiment.value_source.configuration_scope_instance_id");
            if (experiment.getValueSource().getKind() == ValueSourceKind.ENDED_FIXED) {
                if (experiment.hasAssignmentId() && !experiment.getAssignmentId().isEmpty()) {
                    throw invalid(field + ".experiment.assignment_id", "SOURCE_CONFLICT", "结束固定值不能携带分配编号");
                }
            } else if (!experiment.hasAssignmentId() || experiment.getAssignmentId().isBlank()) {
                throw missing(field + ".experiment.assignment_id", "实验取值需要已确认分配编号");
            }
        }
    }

    private static void valueSource(ValueSource source, String field) {
        if (source.hasConfigurationVersion()) positive(source.getConfigurationVersion(), field + ".configuration_version");
        if (source.hasCandidateId()) required(source.getCandidateId(), field + ".candidate_id");
        if (source.getKind() != ValueSourceKind.EXPERIMENT && source.hasCandidateId()) {
            throw invalid(field + ".candidate_id", "SOURCE_CONFLICT", "只有实验候选来源可以携带候选编号");
        }
        switch (source.getKind()) {
            case ORDINARY_DEFAULT -> { }
            case EXPERIMENT, ENDED_FIXED -> {
                required(source.getConfigurationScopeInstanceId(), field + ".configuration_scope_instance_id");
                if (!source.hasConfigurationVersion()) throw missing(field + ".configuration_version", "实验来源需要明确配置版本");
                if (source.getKind() == ValueSourceKind.EXPERIMENT && !source.hasCandidateId()) {
                    throw missing(field + ".candidate_id", "实验来源需要实际候选编号");
                }
            }
            default -> throw missing(field + ".kind", "指令来源需要明确取值类型");
        }
    }

    private static JsonNode inputObject(String text) {
        try {
            JsonNode body = JSON.readTree(text);
            if (body != null && body.isObject()) return body;
        } catch (Exception invalid) {
            throw invalid("input_json", "INVALID_JSON", "输入需要是一个完整JSON对象");
        }
        throw invalid("input_json", "INVALID_JSON", "输入需要是一个完整JSON对象");
    }

    private static void storedMessage(Message message, String field) {
        for (Map.Entry<Descriptors.FieldDescriptor, Object> entry : message.getAllFields().entrySet()) {
            String child = field + "." + entry.getKey().getName();
            if (entry.getKey().isRepeated()) {
                List<?> values = (List<?>) entry.getValue();
                for (int i = 0; i < values.size(); i++) storedValue(values.get(i), child + "[" + i + "]");
            } else storedValue(entry.getValue(), child);
        }
    }

    private static void storedValue(Object value, String field) {
        if (value instanceof String text) storedText(text, field);
        else if (value instanceof Message message) storedMessage(message, field);
    }

    private static byte[] storedText(String text, String field) {
        if (text.indexOf('\0') >= 0) throw invalid(field, "UNSUPPORTED_NUL", "待保存字段不能包含U+0000字符");
        try {
            ByteBuffer buffer = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return bytes;
        } catch (CharacterCodingException failure) {
            throw invalid(field, "INVALID_UTF8", "待保存字段包含无法按UTF-8保存的字符");
        }
    }

    private static void uuid(String value, String field) {
        try {
            if (UUID.fromString(value).toString().equalsIgnoreCase(value)) return;
        } catch (IllegalArgumentException ignored) { }
        throw invalid(field, "INVALID_UUID", "调用与尝试编号需要完整UUID");
    }

    private static String text(JsonNode value, String field) {
        if (value == null || !value.isTextual()) throw invalid(field, "REQUIRED", "实际工具或工具结果需要明确名称或调用编号");
        required(value.textValue(), field);
        return value.textValue();
    }

    private static void scope(PromptScope scope, String field) {
        required(scope.getEnvironment(), field + ".environment");
        required(scope.getScopeInstanceId(), field + ".scope_instance_id");
    }

    private static void optional(String value, String field) {
        if (!value.isEmpty()) required(value, field);
    }

    private static void required(String value, String field) {
        if (value.isBlank()) throw invalid(field, "REQUIRED", "字段需要非空文本");
    }

    private static void positive(long value, String field) {
        if (value <= 0) throw invalid(field, "INVALID_VERSION", "版本需要为正整数");
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK缺少SHA-256");
        }
    }

    private static PromptBusinessException invalid(String field, String code, String reason) {
        return error("INVALID_ARGUMENT", field, code, reason);
    }

    private static PromptBusinessException missing(String field, String reason) {
        return error("PROMPT_SOURCE_MISSING", field, "SOURCE_REQUIRED", reason);
    }

    private static PromptBusinessException error(String code, String field, String detail, String reason) {
        return new PromptBusinessException(RacingError.newBuilder().setCode(code).setMessage("模型输入未通过检查")
                .putObjectLocator("field", field).putDetails("reason_code", detail)
                .addFieldViolations(FieldViolation.newBuilder().setField(field).setCode(detail).setMessage(reason)).build());
    }

    private record Position(ModelInputSourceBinding.LocationCase kind, int messageIndex, String name,
                            boolean instruction, String coveredBy, String safeField) { }

    /** 原请求不可变；长度与摘要来自原input_json的UTF-8字节，不重写正文。 */
    public record Validated(StoreModelInputRequest request, long utf8Length, String sha256) {
        @Override
        public String toString() {
            return "ValidatedModelInput[utf8Length=" + utf8Length + ", sha256=" + sha256
                    + ", sourceCount=" + request.getSourcesCount() + "]";
        }
    }
}
