package world.willfrog.racing.server.prompt;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import world.willfrog.racing.idl.FieldViolation;
import world.willfrog.racing.idl.PromptVariableDeclaration;
import world.willfrog.racing.idl.PromptVersionInput;

/** 在保存前核对正文与声明，保留原字符、换行和BOM，不执行模板替换。 */
public final class PromptContentValidator {

    public ValidatedPromptContent validate(PromptVersionInput input) {
        if (input == null) {
            throw PromptBusinessException.invalid(List.of(violation("content", "REQUIRED", "需要提供提示词内容")));
        }
        List<FieldViolation> errors = new ArrayList<>();
        requiredText(input.getPurpose(), "purpose", errors);
        byte[] body = storedText(input.getBody(), "body", errors);
        Map<String, Integer> declarations = validateVariables(input.getVariablesList(), errors);
        switch (input.getBodyType()) {
            case PROMPT_BODY_FIXED -> {
                if (input.getVariablesCount() > 0) {
                    add(errors, "variables", "PROMPT_VARIABLE_INVALID", "固定正文不能声明模板变量");
                }
            }
            case PROMPT_BODY_TEMPLATE -> validatePlaceholders(input.getBody(), declarations, errors);
            default -> add(errors, "body_type", "INVALID_BODY_TYPE", "需要明确固定正文或变量模板");
        }
        for (int i = 0; i < input.getReadableScopesCount(); i++) {
            String path = "readable_scopes[" + i + "]";
            requiredText(input.getReadableScopes(i).getEnvironment(), path + ".environment", errors);
            requiredText(input.getReadableScopes(i).getScopeInstanceId(), path + ".scope_instance_id", errors);
        }
        // 空授权清单原样保留；读取业务不能把它解释为公开授权。
        if (input.hasImportOrigin()) {
            requiredText(input.getImportOrigin().getSourceName(), "import_origin.source_name", errors);
            requiredText(input.getImportOrigin().getSourceRevision(), "import_origin.source_revision", errors);
            String digest = input.getImportOrigin().getSourceSha256();
            requiredText(digest, "import_origin.source_sha256", errors);
            if (!digest.isBlank() && !validSha256(digest)) {
                add(errors, "import_origin.source_sha256", "INVALID_SHA256", "来源摘要需要64位十六进制SHA-256");
            }
        }
        if (!errors.isEmpty()) {
            throw PromptBusinessException.invalid(errors);
        }
        return new ValidatedPromptContent(input, body.length, sha256(body));
    }

    private static Map<String, Integer> validateVariables(List<PromptVariableDeclaration> variables,
                                                          List<FieldViolation> errors) {
        Map<String, Integer> names = new LinkedHashMap<>();
        for (int i = 0; i < variables.size(); i++) {
            PromptVariableDeclaration variable = variables.get(i);
            String path = "variables[" + i + "]";
            storedText(variable.getName(), path + ".name", errors);
            if (!validVariableName(variable.getName())) {
                add(errors, path + ".name", "PROMPT_VARIABLE_INVALID", "变量名不能为空、包含花括号或ISO控制字符");
            }
            if (names.putIfAbsent(variable.getName(), i) != null) {
                add(errors, path + ".name", "PROMPT_VARIABLE_INVALID", "变量声明不能重复");
            }
            if (!variable.getRequired() && !variable.hasDefaultValue()) {
                add(errors, path + ".default_value", "PROMPT_VARIABLE_INVALID", "可选变量需要声明默认文本");
            }
            if (variable.hasDefaultValue()) {
                storedText(variable.getDefaultValue(), path + ".default_value", errors);
                if (variable.getDefaultValue().isEmpty() && !variable.getAllowEmpty()) {
                    add(errors, path + ".default_value", "PROMPT_VARIABLE_INVALID", "当前变量规则不允许空串默认值");
                }
            }
        }
        return names;
    }

    private static void validatePlaceholders(String body, Map<String, Integer> declarations,
                                             List<FieldViolation> errors) {
        Set<String> used = new LinkedHashSet<>();
        int cursor = 0;
        int opening;
        while ((opening = body.indexOf("{{", cursor)) >= 0) {
            int closing = body.indexOf("}}", opening + 2);
            if (closing < 0) {
                add(errors, "body", "PROMPT_VARIABLE_INVALID", "模板占位符缺少结束分隔符");
                break;
            }
            String name = body.substring(opening + 2, closing);
            if (!validVariableName(name)) {
                add(errors, "body", "PROMPT_VARIABLE_INVALID", "模板占位符中的变量名无效");
            } else if (!declarations.containsKey(name)) {
                add(errors, "body", "PROMPT_VARIABLE_INVALID", "模板占位符缺少对应变量声明");
            }
            used.add(name);
            cursor = closing + 2;
        }
        for (Map.Entry<String, Integer> declaration : declarations.entrySet()) {
            if (!used.contains(declaration.getKey())) {
                add(errors, "variables[" + declaration.getValue() + "].name", "PROMPT_VARIABLE_INVALID",
                        "变量声明没有对应模板占位符");
            }
        }
    }

    private static boolean validVariableName(String name) {
        // 与调用侧按原文匹配，不收窄为ASCII标识符，也不裁剪变量名。
        return !name.isBlank() && name.indexOf('{') < 0 && name.indexOf('}') < 0
                && name.chars().noneMatch(Character::isISOControl);
    }

    private static void requiredText(String value, String field, List<FieldViolation> errors) {
        if (value.isBlank()) {
            add(errors, field, "REQUIRED", "需要提供非空文本");
        }
        storedText(value, field, errors);
    }

    private static byte[] storedText(String value, String field, List<FieldViolation> errors) {
        // PostgreSQL TEXT和JSONB不能保存U+0000；拒绝而不替换或截断。
        if (value.indexOf('\0') >= 0) {
            add(errors, field, "UNSUPPORTED_NUL", "字段不能包含U+0000字符");
        }
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException failure) {
            add(errors, field, "INVALID_UTF8", "字段包含无法按UTF-8保存的字符");
            return new byte[0];
        }
    }

    private static boolean validSha256(String value) {
        if (value.length() != 64) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char digit = value.charAt(i);
            if (!(digit >= '0' && digit <= '9') && !(digit >= 'a' && digit <= 'f')
                    && !(digit >= 'A' && digit <= 'F')) {
                return false;
            }
        }
        return true;
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK缺少SHA-256");
        }
    }

    private static void add(List<FieldViolation> errors, String field, String code, String reason) {
        errors.add(violation(field, code, reason));
    }

    private static FieldViolation violation(String field, String code, String reason) {
        return FieldViolation.newBuilder().setField(field).setCode(code).setMessage(reason).build();
    }

    /** 原始消息不可变；摘要和长度仅对应其中的正文UTF-8字节。 */
    public record ValidatedPromptContent(PromptVersionInput input, long utf8Length, String sha256) {
        @Override
        public String toString() {
            return "ValidatedPromptContent[type=" + input.getBodyType() + ", utf8Length=" + utf8Length
                    + ", sha256=" + sha256 + "]";
        }
    }
}
