package world.willfrog.racing.server.rpc;

import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import world.willfrog.racing.idl.*;
import world.willfrog.racing.server.configuration.ExperimentConfigurationService;
import world.willfrog.racing.server.configuration.ExperimentConfigurationException;
import world.willfrog.racing.server.prompt.*;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import world.willfrog.racing.server.evaluation.ExperimentEvaluationService;
import world.willfrog.racing.server.history.ExperimentHistoryService;
import world.willfrog.racing.server.input.ModelInputService;
import world.willfrog.racing.server.result.ExperimentResultService;
import world.willfrog.racing.server.security.DeveloperAuthorization;
import world.willfrog.racing.server.usage.ExperimentUsageService;
import world.willfrog.racing.idl.ConfirmRuntimeContextReply;
import world.willfrog.racing.idl.ConfirmRuntimeContextRequest;
import world.willfrog.racing.idl.DubboRacingServiceTriple.RacingServiceImplBase;
import world.willfrog.racing.server.security.RacingServiceException;
import world.willfrog.racing.server.security.ServiceAuthorization;

/** 统一远程入口；上下文确认不创建实验分配或提示词选择。 */
@DubboService
@Service
public final class RacingServiceImpl extends RacingServiceImplBase {
    private final ServiceAuthorization authorization;

    private final ObjectProvider<ExperimentEvaluationService> evaluations;
    private final ObjectProvider<ExperimentConfigurationService> configurations;
    private final ObjectProvider<ModelInputService> modelInputs;

    private final ObjectProvider<PromptManagementService> promptManagement;
    private final ObjectProvider<PromptReadService> promptReads;
    private final ObjectProvider<PromptSetService> promptSets;
    private final ObjectProvider<PromptSelectionService> promptSelections;
    private final ObjectProvider<DefaultPromptReferenceService> promptReferences;
    private final ObjectProvider<ExperimentHistoryService> histories;
    private final DeveloperAuthorization developers;
    private final ObjectProvider<ExperimentResultService> results;
    private final ObjectProvider<ExperimentUsageService> usages;

    public RacingServiceImpl(ServiceAuthorization authorization,
            ObjectProvider<ExperimentEvaluationService> evaluations,
            ObjectProvider<ExperimentConfigurationService> configurations,
            ObjectProvider<ModelInputService> modelInputs,
            ObjectProvider<PromptManagementService> promptManagement,
            ObjectProvider<PromptReadService> promptReads,
            ObjectProvider<PromptSetService> promptSets,
            ObjectProvider<PromptSelectionService> promptSelections,
            ObjectProvider<DefaultPromptReferenceService> promptReferences,
            ObjectProvider<ExperimentHistoryService> histories,
            DeveloperAuthorization developers,
            ObjectProvider<ExperimentResultService> results,
            ObjectProvider<ExperimentUsageService> usages) {
        this.authorization = authorization;
        this.evaluations = evaluations;
        this.configurations = configurations;
        this.modelInputs = modelInputs;
        this.promptManagement = promptManagement;
        this.promptReads = promptReads;
        this.promptSets = promptSets;
        this.promptSelections = promptSelections;
        this.promptReferences = promptReferences;
        this.histories = histories;
        this.developers = developers;
        this.results = results;
        this.usages = usages;
    }

    /** 输入服务的事务代理提交成功后才会返回，入口据此封装保存回执。 */
    @Override
    public StoreModelInputReply storeModelInput(StoreModelInputRequest request) {
        var reply = StoreModelInputReply.newBuilder();
        try {
            if (request == null || !request.hasContext()) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "模型输入保存请求缺少运行上下文");
            }
            var call = authorization.authorize("StoreModelInput", request.getContext());
            reply.setActualTarget(call.actualTarget());
            var service = modelInputs.getIfAvailable();
            if (service == null) {
                throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "模型输入保存服务尚未配置");
            }
            return reply.setStored(service.store(call, request)).build();
        } catch (RacingServiceException failure) {
            return reply.setError(failure.error()).build();
        } catch (PromptBusinessException failure) {
            return reply.setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return reply.setError(RacingError.newBuilder().setCode("INPUT_STORE_UNCONFIRMED")
                    .setMessage("模型输入尚未确认保存，请使用原尝试编号重试")).build();
        }
    }

    @Override
    public EvaluateReply evaluate(EvaluateRequest request) {
        try {
            if (request == null || !request.hasContext()) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "实验取值请求缺少运行上下文");
            }
            var call = authorization.authorize("Evaluate", request.getContext());
            var service = evaluations.getIfAvailable();
            if (service == null) {
                throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "实验取值服务尚未配置");
            }
            return EvaluateReply.newBuilder().setValue(service.evaluate(call, request)).build();
        } catch (RacingServiceException failure) {
            return EvaluateReply.newBuilder().setError(failure.error()).build();
        } catch (world.willfrog.racing.server.configuration.ExperimentConfigurationException failure) {
            return EvaluateReply.newBuilder().setError(failure.error()).build();
        }
    }

    /** 授权通过后才保存；成功回复带上实际处理这次请求的平台实例。 */
    @Override
    public RecordExperimentUsageReply recordExperimentUsage(RecordExperimentUsageRequest request) {
        var reply = RecordExperimentUsageReply.newBuilder();
        try {
            if (request == null || !request.hasContext()) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "使用上报缺少运行上下文");
            }
            var call = authorization.authorize("RecordExperimentUsage", request.getContext());
            reply.setActualTarget(call.actualTarget());
            var service = usages.getIfAvailable();
            if (service == null) {
                throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "使用记录服务尚未配置");
            }
            return reply.setReceipt(service.record(call, request)).build();
        } catch (RacingServiceException failure) {
            return reply.setError(failure.error()).build();
        } catch (ExperimentConfigurationException failure) {
            return reply.setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return reply.setError(RacingError.newBuilder().setCode("PERSISTENCE_UNCONFIRMED")
                    .setMessage("使用记录尚未确认保存，请用同一次上报重试")).build();
        }
    }

    @Override
    public ConfirmRuntimeContextReply confirmRuntimeContext(ConfirmRuntimeContextRequest request) {
        try {
            if (request == null || !request.hasContext()) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "上下文确认请求缺少运行上下文");
            }
            return ConfirmRuntimeContextReply.newBuilder()
                    .setConfirmed(authorization.confirm(request.getAction(), request.getContext())).build();
        } catch (RacingServiceException failure) {
            return ConfirmRuntimeContextReply.newBuilder().setError(failure.error()).build();
        }
    }

    @Override
    public ExperimentRecordReply createExperiment(CreateExperimentRequest request) {
        var reply = ExperimentRecordReply.newBuilder();
        try {
            if (request == null || !request.hasContext()) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "实验管理请求缺少运行上下文");
            }
            var call = authorization.authorize("CreateExperiment", request.getContext());
            reply.setActualTarget(call.actualTarget());
            var service = configurations.getIfAvailable();
            if (service == null) throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "实验配置服务尚未配置");
            return reply.setExperiment(service.create(call, request)).build();
        } catch (RacingServiceException failure) {
            return reply.setError(failure.error()).build();
        } catch (ExperimentConfigurationException failure) {
            return reply.setError(failure.error()).build();
        } catch (PromptBusinessException failure) {
            return reply.setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return reply.setError(RacingError.newBuilder().setCode("PERSISTENCE_UNCONFIRMED")
                    .setMessage("实验配置操作未确认，请重新查询当前实验状态")).build();
        }
    }

    @Override
    public ExperimentRecordReply saveExperimentDraft(SaveExperimentDraftRequest request) {
        var reply = ExperimentRecordReply.newBuilder();
        try {
            if (request == null || !request.hasContext()) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "实验管理请求缺少运行上下文");
            }
            var call = authorization.authorize("SaveExperimentDraft", request.getContext());
            reply.setActualTarget(call.actualTarget());
            var service = configurations.getIfAvailable();
            if (service == null) throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "实验配置服务尚未配置");
            return reply.setExperiment(service.saveDraft(call, request)).build();
        } catch (RacingServiceException failure) {
            return reply.setError(failure.error()).build();
        } catch (ExperimentConfigurationException failure) {
            return reply.setError(failure.error()).build();
        } catch (PromptBusinessException failure) {
            return reply.setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return reply.setError(RacingError.newBuilder().setCode("PERSISTENCE_UNCONFIRMED")
                    .setMessage("实验配置操作未确认，请重新查询当前实验状态")).build();
        }
    }

    @Override
    public ExperimentRecordReply publishExperiment(ExperimentMutationRequest request) {
        var reply = ExperimentRecordReply.newBuilder();
        try {
            if (request == null || !request.hasContext()) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "实验管理请求缺少运行上下文");
            }
            var call = authorization.authorize("PublishExperiment", request.getContext());
            reply.setActualTarget(call.actualTarget());
            var service = configurations.getIfAvailable();
            if (service == null) throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "实验配置服务尚未配置");
            return reply.setExperiment(service.publish(call, request)).build();
        } catch (RacingServiceException failure) {
            return reply.setError(failure.error()).build();
        } catch (ExperimentConfigurationException failure) {
            return reply.setError(failure.error()).build();
        } catch (PromptBusinessException failure) {
            return reply.setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return reply.setError(RacingError.newBuilder().setCode("PERSISTENCE_UNCONFIRMED")
                    .setMessage("实验配置操作未确认，请重新查询当前实验状态")).build();
        }
    }

    @Override
    public ExperimentRecordReply startExperiment(ExperimentMutationRequest request) {
        var reply = ExperimentRecordReply.newBuilder();
        try {
            if (request == null || !request.hasContext()) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "实验管理请求缺少运行上下文");
            }
            var call = authorization.authorize("StartExperiment", request.getContext());
            reply.setActualTarget(call.actualTarget());
            var service = configurations.getIfAvailable();
            if (service == null) throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "实验配置服务尚未配置");
            return reply.setExperiment(service.start(call, request)).build();
        } catch (RacingServiceException failure) {
            return reply.setError(failure.error()).build();
        } catch (ExperimentConfigurationException failure) {
            return reply.setError(failure.error()).build();
        } catch (PromptBusinessException failure) {
            return reply.setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return reply.setError(RacingError.newBuilder().setCode("PERSISTENCE_UNCONFIRMED")
                    .setMessage("实验配置操作未确认，请重新查询当前实验状态")).build();
        }
    }

    @Override
    public ExperimentRecordReply pauseExperiment(ExperimentMutationRequest request) {
        var reply = ExperimentRecordReply.newBuilder();
        try {
            if (request == null || !request.hasContext()) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "实验管理请求缺少运行上下文");
            }
            var call = authorization.authorize("PauseExperiment", request.getContext());
            reply.setActualTarget(call.actualTarget());
            var service = configurations.getIfAvailable();
            if (service == null) throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "实验配置服务尚未配置");
            return reply.setExperiment(service.pause(call, request)).build();
        } catch (RacingServiceException failure) {
            return reply.setError(failure.error()).build();
        } catch (ExperimentConfigurationException failure) {
            return reply.setError(failure.error()).build();
        } catch (PromptBusinessException failure) {
            return reply.setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return reply.setError(RacingError.newBuilder().setCode("PERSISTENCE_UNCONFIRMED")
                    .setMessage("实验配置操作未确认，请重新查询当前实验状态")).build();
        }
    }

    @Override
    public ExperimentRecordReply resumeExperiment(ExperimentMutationRequest request) {
        var reply = ExperimentRecordReply.newBuilder();
        try {
            if (request == null || !request.hasContext()) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "实验管理请求缺少运行上下文");
            }
            var call = authorization.authorize("ResumeExperiment", request.getContext());
            reply.setActualTarget(call.actualTarget());
            var service = configurations.getIfAvailable();
            if (service == null) throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "实验配置服务尚未配置");
            return reply.setExperiment(service.resume(call, request)).build();
        } catch (RacingServiceException failure) {
            return reply.setError(failure.error()).build();
        } catch (ExperimentConfigurationException failure) {
            return reply.setError(failure.error()).build();
        } catch (PromptBusinessException failure) {
            return reply.setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return reply.setError(RacingError.newBuilder().setCode("PERSISTENCE_UNCONFIRMED")
                    .setMessage("实验配置操作未确认，请重新查询当前实验状态")).build();
        }
    }

    @Override
    public ExperimentRecordReply endExperiment(EndExperimentRequest request) {
        var reply = ExperimentRecordReply.newBuilder();
        try {
            if (request == null || !request.hasContext()) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "实验管理请求缺少运行上下文");
            }
            var call = authorization.authorize("EndExperiment", request.getContext());
            reply.setActualTarget(call.actualTarget());
            var service = configurations.getIfAvailable();
            if (service == null) throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "实验配置服务尚未配置");
            return reply.setExperiment(service.end(call, request)).build();
        } catch (RacingServiceException failure) {
            return reply.setError(failure.error()).build();
        } catch (ExperimentConfigurationException failure) {
            return reply.setError(failure.error()).build();
        } catch (PromptBusinessException failure) {
            return reply.setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return reply.setError(RacingError.newBuilder().setCode("PERSISTENCE_UNCONFIRMED")
                    .setMessage("实验配置操作未确认，请重新查询当前实验状态")).build();
        }
    }

    @Override
    public PreviewExperimentDeleteReply previewExperimentDelete(GetExperimentRequest request) {
        var reply = PreviewExperimentDeleteReply.newBuilder();
        try {
            if (request == null || !request.hasContext()) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "实验管理请求缺少运行上下文");
            }
            var call = authorization.authorize("PreviewExperimentDelete", request.getContext());
            reply.setActualTarget(call.actualTarget());
            var service = configurations.getIfAvailable();
            if (service == null) throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "实验配置服务尚未配置");
            return reply.setPreview(service.previewDelete(call, request)).build();
        } catch (RacingServiceException failure) {
            return reply.setError(failure.error()).build();
        } catch (ExperimentConfigurationException failure) {
            return reply.setError(failure.error()).build();
        } catch (PromptBusinessException failure) {
            return reply.setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return reply.setError(RacingError.newBuilder().setCode("PERSISTENCE_UNCONFIRMED")
                    .setMessage("实验配置操作未确认，请重新查询当前实验状态")).build();
        }
    }

    @Override
    public ExperimentRecordReply deleteExperiment(ExperimentMutationRequest request) {
        var reply = ExperimentRecordReply.newBuilder();
        try {
            if (request == null || !request.hasContext()) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "实验管理请求缺少运行上下文");
            }
            var call = authorization.authorize("DeleteExperiment", request.getContext());
            reply.setActualTarget(call.actualTarget());
            var service = configurations.getIfAvailable();
            if (service == null) throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "实验配置服务尚未配置");
            return reply.setExperiment(service.delete(call, request)).build();
        } catch (RacingServiceException failure) {
            return reply.setError(failure.error()).build();
        } catch (ExperimentConfigurationException failure) {
            return reply.setError(failure.error()).build();
        } catch (PromptBusinessException failure) {
            return reply.setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return reply.setError(RacingError.newBuilder().setCode("PERSISTENCE_UNCONFIRMED")
                    .setMessage("实验配置操作未确认，请重新查询当前实验状态")).build();
        }
    }

    @Override
    public ExperimentRecordReply getExperiment(GetExperimentRequest request) {
        var reply = ExperimentRecordReply.newBuilder();
        try {
            if (request == null || !request.hasContext()) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "实验管理请求缺少运行上下文");
            }
            var call = authorization.authorize("GetExperiment", request.getContext());
            reply.setActualTarget(call.actualTarget());
            var service = configurations.getIfAvailable();
            if (service == null) throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "实验配置服务尚未配置");
            return reply.setExperiment(service.get(call, request)).build();
        } catch (RacingServiceException failure) {
            return reply.setError(failure.error()).build();
        } catch (ExperimentConfigurationException failure) {
            return reply.setError(failure.error()).build();
        } catch (PromptBusinessException failure) {
            return reply.setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return reply.setError(RacingError.newBuilder().setCode("PERSISTENCE_UNCONFIRMED")
                    .setMessage("实验配置操作未确认，请重新查询当前实验状态")).build();
        }
    }

    @Override
    public ListExperimentsReply listExperiments(ListExperimentsRequest request) {
        var reply = ListExperimentsReply.newBuilder();
        try {
            if (request == null || !request.hasContext()) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "实验管理请求缺少运行上下文");
            }
            var call = authorization.authorize("ListExperiments", request.getContext());
            reply.setActualTarget(call.actualTarget());
            var service = configurations.getIfAvailable();
            if (service == null) throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "实验配置服务尚未配置");
            return reply.setRecords(service.list(call, request)).build();
        } catch (RacingServiceException failure) {
            return reply.setError(failure.error()).build();
        } catch (ExperimentConfigurationException failure) {
            return reply.setError(failure.error()).build();
        } catch (PromptBusinessException failure) {
            return reply.setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return reply.setError(RacingError.newBuilder().setCode("PERSISTENCE_UNCONFIRMED")
                    .setMessage("实验配置操作未确认，请重新查询当前实验状态")).build();
        }
    }

    @Override
    public ListExperimentVersionsReply listExperimentVersions(GetExperimentRequest request) {
        var reply = ListExperimentVersionsReply.newBuilder();
        try {
            if (request == null || !request.hasContext()) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "实验管理请求缺少运行上下文");
            }
            var call = authorization.authorize("ListExperimentVersions", request.getContext());
            reply.setActualTarget(call.actualTarget());
            var service = configurations.getIfAvailable();
            if (service == null) throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "实验配置服务尚未配置");
            return reply.setRecords(service.listVersions(call, request)).build();
        } catch (RacingServiceException failure) {
            return reply.setError(failure.error()).build();
        } catch (ExperimentConfigurationException failure) {
            return reply.setError(failure.error()).build();
        } catch (PromptBusinessException failure) {
            return reply.setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return reply.setError(RacingError.newBuilder().setCode("PERSISTENCE_UNCONFIRMED")
                    .setMessage("实验配置操作未确认，请重新查询当前实验状态")).build();
        }
    }

    @Override
    public ListExperimentOperationsReply listExperimentOperations(GetExperimentRequest request) {
        var reply = ListExperimentOperationsReply.newBuilder();
        try {
            if (request == null || !request.hasContext()) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "实验管理请求缺少运行上下文");
            }
            var call = authorization.authorize("ListExperimentOperations", request.getContext());
            reply.setActualTarget(call.actualTarget());
            var service = configurations.getIfAvailable();
            if (service == null) throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "实验配置服务尚未配置");
            return reply.setRecords(service.listOperations(call, request)).build();
        } catch (RacingServiceException failure) {
            return reply.setError(failure.error()).build();
        } catch (ExperimentConfigurationException failure) {
            return reply.setError(failure.error()).build();
        } catch (PromptBusinessException failure) {
            return reply.setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return reply.setError(RacingError.newBuilder().setCode("PERSISTENCE_UNCONFIRMED")
                    .setMessage("实验配置操作未确认，请重新查询当前实验状态")).build();
        }
    }

    @Override
    public PromptDetailsReply createPrompt(CreatePromptRequest request) {
        var reply = PromptDetailsReply.newBuilder();
        try {
            if (request == null || !request.hasContext()) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "提示词请求缺少运行上下文");
            }
            var call = authorization.authorize("CreatePrompt", request.getContext());
            reply.setActualTarget(call.actualTarget());
            var service = promptManagement.getIfAvailable();
            if (service == null) throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "提示词服务尚未配置");
            return reply.setPrompt(service.create(call, request)).build();
        } catch (RacingServiceException failure) {
            return reply.setError(failure.error()).build();
        } catch (PromptBusinessException failure) {
            return reply.setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return reply.setError(RacingError.newBuilder().setCode("PERSISTENCE_UNCONFIRMED")
                    .setMessage("提示词操作尚未确认，请重新查询当前状态")).build();
        }
    }

    @Override
    public PromptRecordReply updatePromptMetadata(UpdatePromptMetadataRequest request) {
        var reply = PromptRecordReply.newBuilder();
        try {
            if (request == null || !request.hasContext()) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "提示词请求缺少运行上下文");
            }
            var call = authorization.authorize("UpdatePromptMetadata", request.getContext());
            reply.setActualTarget(call.actualTarget());
            var service = promptManagement.getIfAvailable();
            if (service == null) throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "提示词服务尚未配置");
            return reply.setPrompt(service.updateMetadata(call, request)).build();
        } catch (RacingServiceException failure) {
            return reply.setError(failure.error()).build();
        } catch (PromptBusinessException failure) {
            return reply.setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return reply.setError(RacingError.newBuilder().setCode("PERSISTENCE_UNCONFIRMED")
                    .setMessage("提示词操作尚未确认，请重新查询当前状态")).build();
        }
    }

    @Override
    public PromptDetailsReply createPromptVersion(CreatePromptVersionRequest request) {
        var reply = PromptDetailsReply.newBuilder();
        try {
            if (request == null || !request.hasContext()) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "提示词请求缺少运行上下文");
            }
            var call = authorization.authorize("CreatePromptVersion", request.getContext());
            reply.setActualTarget(call.actualTarget());
            var service = promptManagement.getIfAvailable();
            if (service == null) throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "提示词服务尚未配置");
            return reply.setPrompt(service.createVersion(call, request)).build();
        } catch (RacingServiceException failure) {
            return reply.setError(failure.error()).build();
        } catch (PromptBusinessException failure) {
            return reply.setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return reply.setError(RacingError.newBuilder().setCode("PERSISTENCE_UNCONFIRMED")
                    .setMessage("提示词操作尚未确认，请重新查询当前状态")).build();
        }
    }

    @Override
    public PromptVersionReply updatePromptVersionAuthorization(UpdatePromptVersionAuthorizationRequest request) {
        var reply = PromptVersionReply.newBuilder();
        try {
            if (request == null || !request.hasContext()) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "提示词请求缺少运行上下文");
            }
            var call = authorization.authorize("UpdatePromptVersionAuthorization", request.getContext());
            reply.setActualTarget(call.actualTarget());
            var service = promptManagement.getIfAvailable();
            if (service == null) throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "提示词服务尚未配置");
            return reply.setVersion(service.updateAuthorization(call, request)).build();
        } catch (RacingServiceException failure) {
            return reply.setError(failure.error()).build();
        } catch (PromptBusinessException failure) {
            return reply.setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return reply.setError(RacingError.newBuilder().setCode("PERSISTENCE_UNCONFIRMED")
                    .setMessage("提示词操作尚未确认，请重新查询当前状态")).build();
        }
    }

    @Override
    public PromptRecordReply setPromptDefault(SetPromptDefaultRequest request) {
        var reply = PromptRecordReply.newBuilder();
        try {
            if (request == null || !request.hasContext()) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "提示词请求缺少运行上下文");
            }
            var call = authorization.authorize("SetPromptDefault", request.getContext());
            reply.setActualTarget(call.actualTarget());
            var service = promptManagement.getIfAvailable();
            if (service == null) throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "提示词服务尚未配置");
            return reply.setPrompt(service.setDefault(call, request)).build();
        } catch (RacingServiceException failure) {
            return reply.setError(failure.error()).build();
        } catch (PromptBusinessException failure) {
            return reply.setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return reply.setError(RacingError.newBuilder().setCode("PERSISTENCE_UNCONFIRMED")
                    .setMessage("提示词操作尚未确认，请重新查询当前状态")).build();
        }
    }

    @Override
    public PromptDetailsReply getPrompt(GetPromptRequest request) {
        var reply = PromptDetailsReply.newBuilder();
        try {
            if (request == null || !request.hasContext()) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "提示词请求缺少运行上下文");
            }
            var call = authorization.authorize("GetPrompt", request.getContext());
            reply.setActualTarget(call.actualTarget());
            var service = promptReads.getIfAvailable();
            if (service == null) throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "提示词服务尚未配置");
            return reply.setPrompt(service.get(call, request)).build();
        } catch (RacingServiceException failure) {
            return reply.setError(failure.error()).build();
        } catch (PromptBusinessException failure) {
            return reply.setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return reply.setError(RacingError.newBuilder().setCode("PERSISTENCE_UNCONFIRMED")
                    .setMessage("提示词操作尚未确认，请重新查询当前状态")).build();
        }
    }

    @Override
    public ListPromptsReply listPrompts(ListPromptsRequest request) {
        var reply = ListPromptsReply.newBuilder();
        try {
            if (request == null || !request.hasContext()) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "提示词请求缺少运行上下文");
            }
            var call = authorization.authorize("ListPrompts", request.getContext());
            reply.setActualTarget(call.actualTarget());
            var service = promptReads.getIfAvailable();
            if (service == null) throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "提示词服务尚未配置");
            return reply.setPage(service.list(call, request)).build();
        } catch (RacingServiceException failure) {
            return reply.setError(failure.error()).build();
        } catch (PromptBusinessException failure) {
            return reply.setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return reply.setError(RacingError.newBuilder().setCode("PERSISTENCE_UNCONFIRMED")
                    .setMessage("提示词操作尚未确认，请重新查询当前状态")).build();
        }
    }

    @Override
    public ListPromptVersionsReply listPromptVersions(ListPromptVersionsRequest request) {
        var reply = ListPromptVersionsReply.newBuilder();
        try {
            if (request == null || !request.hasContext()) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "提示词请求缺少运行上下文");
            }
            var call = authorization.authorize("ListPromptVersions", request.getContext());
            reply.setActualTarget(call.actualTarget());
            var service = promptReads.getIfAvailable();
            if (service == null) throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "提示词服务尚未配置");
            return reply.setPage(service.listVersions(call, request)).build();
        } catch (RacingServiceException failure) {
            return reply.setError(failure.error()).build();
        } catch (PromptBusinessException failure) {
            return reply.setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return reply.setError(RacingError.newBuilder().setCode("PERSISTENCE_UNCONFIRMED")
                    .setMessage("提示词操作尚未确认，请重新查询当前状态")).build();
        }
    }

    @Override
    public ReadPromptVersionsReply readPromptVersions(ReadPromptVersionsRequest request) {
        var reply = ReadPromptVersionsReply.newBuilder();
        try {
            if (request == null || !request.hasContext()) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "提示词请求缺少运行上下文");
            }
            var call = authorization.authorize("ReadPromptVersions", request.getContext());
            reply.setActualTarget(call.actualTarget());
            var service = promptReads.getIfAvailable();
            if (service == null) throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "提示词服务尚未配置");
            return reply.setVersions(service.getVersions(call, request)).build();
        } catch (RacingServiceException failure) {
            return reply.setError(failure.error()).build();
        } catch (PromptBusinessException failure) {
            return reply.setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return reply.setError(RacingError.newBuilder().setCode("PERSISTENCE_UNCONFIRMED")
                    .setMessage("提示词操作尚未确认，请重新查询当前状态")).build();
        }
    }

    @Override
    public PromptSetRecordReply createPromptSet(CreatePromptSetRequest request) {
        var reply = PromptSetRecordReply.newBuilder();
        try {
            if (request == null || !request.hasContext()) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "提示词请求缺少运行上下文");
            }
            var call = authorization.authorize("CreatePromptSet", request.getContext());
            reply.setActualTarget(call.actualTarget());
            var service = promptSets.getIfAvailable();
            if (service == null) throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "提示词服务尚未配置");
            return reply.setPromptSet(service.create(call, request)).build();
        } catch (RacingServiceException failure) {
            return reply.setError(failure.error()).build();
        } catch (PromptBusinessException failure) {
            return reply.setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return reply.setError(RacingError.newBuilder().setCode("PERSISTENCE_UNCONFIRMED")
                    .setMessage("提示词操作尚未确认，请重新查询当前状态")).build();
        }
    }

    @Override
    public PromptSetRecordReply updatePromptSetMetadata(UpdatePromptSetMetadataRequest request) {
        var reply = PromptSetRecordReply.newBuilder();
        try {
            if (request == null || !request.hasContext()) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "提示词请求缺少运行上下文");
            }
            var call = authorization.authorize("UpdatePromptSetMetadata", request.getContext());
            reply.setActualTarget(call.actualTarget());
            var service = promptSets.getIfAvailable();
            if (service == null) throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "提示词服务尚未配置");
            return reply.setPromptSet(service.updateMetadata(call, request)).build();
        } catch (RacingServiceException failure) {
            return reply.setError(failure.error()).build();
        } catch (PromptBusinessException failure) {
            return reply.setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return reply.setError(RacingError.newBuilder().setCode("PERSISTENCE_UNCONFIRMED")
                    .setMessage("提示词操作尚未确认，请重新查询当前状态")).build();
        }
    }

    @Override
    public PromptSetDetailsReply createPromptSetVersion(CreatePromptSetVersionRequest request) {
        var reply = PromptSetDetailsReply.newBuilder();
        try {
            if (request == null || !request.hasContext()) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "提示词请求缺少运行上下文");
            }
            var call = authorization.authorize("CreatePromptSetVersion", request.getContext());
            reply.setActualTarget(call.actualTarget());
            var service = promptSets.getIfAvailable();
            if (service == null) throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "提示词服务尚未配置");
            return reply.setPromptSet(service.createVersion(call, request)).build();
        } catch (RacingServiceException failure) {
            return reply.setError(failure.error()).build();
        } catch (PromptBusinessException failure) {
            return reply.setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return reply.setError(RacingError.newBuilder().setCode("PERSISTENCE_UNCONFIRMED")
                    .setMessage("提示词操作尚未确认，请重新查询当前状态")).build();
        }
    }

    @Override
    public PromptSetDetailsReply getPromptSet(GetPromptSetRequest request) {
        var reply = PromptSetDetailsReply.newBuilder();
        try {
            if (request == null || !request.hasContext()) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "提示词请求缺少运行上下文");
            }
            var call = authorization.authorize("GetPromptSet", request.getContext());
            reply.setActualTarget(call.actualTarget());
            var service = promptSets.getIfAvailable();
            if (service == null) throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "提示词服务尚未配置");
            return reply.setPromptSet(service.get(call, request)).build();
        } catch (RacingServiceException failure) {
            return reply.setError(failure.error()).build();
        } catch (PromptBusinessException failure) {
            return reply.setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return reply.setError(RacingError.newBuilder().setCode("PERSISTENCE_UNCONFIRMED")
                    .setMessage("提示词操作尚未确认，请重新查询当前状态")).build();
        }
    }

    @Override
    public ListPromptSetsReply listPromptSets(ListPromptSetsRequest request) {
        var reply = ListPromptSetsReply.newBuilder();
        try {
            if (request == null || !request.hasContext()) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "提示词请求缺少运行上下文");
            }
            var call = authorization.authorize("ListPromptSets", request.getContext());
            reply.setActualTarget(call.actualTarget());
            var service = promptSets.getIfAvailable();
            if (service == null) throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "提示词服务尚未配置");
            return reply.setPage(service.list(call, request)).build();
        } catch (RacingServiceException failure) {
            return reply.setError(failure.error()).build();
        } catch (PromptBusinessException failure) {
            return reply.setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return reply.setError(RacingError.newBuilder().setCode("PERSISTENCE_UNCONFIRMED")
                    .setMessage("提示词操作尚未确认，请重新查询当前状态")).build();
        }
    }

    @Override
    public ListPromptSetVersionsReply listPromptSetVersions(ListPromptSetVersionsRequest request) {
        var reply = ListPromptSetVersionsReply.newBuilder();
        try {
            if (request == null || !request.hasContext()) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "提示词请求缺少运行上下文");
            }
            var call = authorization.authorize("ListPromptSetVersions", request.getContext());
            reply.setActualTarget(call.actualTarget());
            var service = promptSets.getIfAvailable();
            if (service == null) throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "提示词服务尚未配置");
            return reply.setPage(service.listVersions(call, request)).build();
        } catch (RacingServiceException failure) {
            return reply.setError(failure.error()).build();
        } catch (PromptBusinessException failure) {
            return reply.setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return reply.setError(RacingError.newBuilder().setCode("PERSISTENCE_UNCONFIRMED")
                    .setMessage("提示词操作尚未确认，请重新查询当前状态")).build();
        }
    }

    @Override
    public PromptSetDefaultReply setPromptSetDefault(SetPromptSetDefaultRequest request) {
        var reply = PromptSetDefaultReply.newBuilder();
        try {
            if (request == null || !request.hasContext()) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "提示词请求缺少运行上下文");
            }
            var call = authorization.authorize("SetPromptSetDefault", request.getContext());
            reply.setActualTarget(call.actualTarget());
            var service = promptSets.getIfAvailable();
            if (service == null) throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "提示词服务尚未配置");
            return reply.setDefaultSet(service.setDefault(call, request)).build();
        } catch (RacingServiceException failure) {
            return reply.setError(failure.error()).build();
        } catch (PromptBusinessException failure) {
            return reply.setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return reply.setError(RacingError.newBuilder().setCode("PERSISTENCE_UNCONFIRMED")
                    .setMessage("提示词操作尚未确认，请重新查询当前状态")).build();
        }
    }

    @Override
    public PromptSetDefaultReply getPromptSetDefault(GetPromptSetDefaultRequest request) {
        var reply = PromptSetDefaultReply.newBuilder();
        try {
            if (request == null || !request.hasContext()) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "提示词请求缺少运行上下文");
            }
            var call = authorization.authorize("GetPromptSetDefault", request.getContext());
            reply.setActualTarget(call.actualTarget());
            var service = promptSets.getIfAvailable();
            if (service == null) throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "提示词服务尚未配置");
            return reply.setDefaultSet(service.getDefault(call, request)).build();
        } catch (RacingServiceException failure) {
            return reply.setError(failure.error()).build();
        } catch (PromptBusinessException failure) {
            return reply.setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return reply.setError(RacingError.newBuilder().setCode("PERSISTENCE_UNCONFIRMED")
                    .setMessage("提示词操作尚未确认，请重新查询当前状态")).build();
        }
    }

    @Override
    public PromptSetVersionReply readPromptSetVersion(ReadPromptSetVersionRequest request) {
        var reply = PromptSetVersionReply.newBuilder();
        try {
            if (request == null || !request.hasContext()) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "提示词请求缺少运行上下文");
            }
            var call = authorization.authorize("ReadPromptSetVersion", request.getContext());
            reply.setActualTarget(call.actualTarget());
            var service = promptSets.getIfAvailable();
            if (service == null) throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "提示词服务尚未配置");
            return reply.setVersion(service.readVersion(call, request.getVersion())).build();
        } catch (RacingServiceException failure) {
            return reply.setError(failure.error()).build();
        } catch (PromptBusinessException failure) {
            return reply.setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return reply.setError(RacingError.newBuilder().setCode("PERSISTENCE_UNCONFIRMED")
                    .setMessage("提示词操作尚未确认，请重新查询当前状态")).build();
        }
    }

    @Override
    public PromptSelectionReply resolvePromptSelection(ResolvePromptSelectionRequest request) {
        var reply = PromptSelectionReply.newBuilder();
        try {
            if (request == null || !request.hasContext()) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "提示词请求缺少运行上下文");
            }
            var call = authorization.authorize("ResolvePromptSelection", request.getContext());
            if (!request.getExperimentId().isEmpty()) {
                authorization.authorize("Evaluate", request.getContext());
            }
            reply.setActualTarget(call.actualTarget());
            var service = promptSelections.getIfAvailable();
            if (service == null) throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "提示词服务尚未配置");
            return reply.setSelection(service.resolve(call, request)).build();
        } catch (RacingServiceException failure) {
            return reply.setError(failure.error()).build();
        } catch (ExperimentConfigurationException failure) {
            return reply.setError(failure.error()).build();
        } catch (PromptBusinessException failure) {
            return reply.setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return reply.setError(RacingError.newBuilder().setCode("PERSISTENCE_UNCONFIRMED")
                    .setMessage("提示词操作尚未确认，请重新查询当前状态")).build();
        }
    }

    @Override
    public PromptSelectionReply getPromptSelection(GetPromptSelectionRequest request) {
        var reply = PromptSelectionReply.newBuilder();
        try {
            if (request == null || !request.hasContext()) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "提示词请求缺少运行上下文");
            }
            var call = authorization.authorize("GetPromptSelection", request.getContext());
            reply.setActualTarget(call.actualTarget());
            var service = promptSelections.getIfAvailable();
            if (service == null) throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "提示词服务尚未配置");
            return reply.setSelection(service.get(call, request)).build();
        } catch (RacingServiceException failure) {
            return reply.setError(failure.error()).build();
        } catch (PromptBusinessException failure) {
            return reply.setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return reply.setError(RacingError.newBuilder().setCode("PERSISTENCE_UNCONFIRMED")
                    .setMessage("提示词操作尚未确认，请重新查询当前状态")).build();
        }
    }

    @Override
    public ListPromptReferencesReply listPromptReferences(ListPromptReferencesRequest request) {
        var reply = ListPromptReferencesReply.newBuilder();
        try {
            if (request == null || !request.hasContext()) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "提示词请求缺少运行上下文");
            }
            var call = authorization.authorize("ListPromptReferences", request.getContext());
            reply.setActualTarget(call.actualTarget());
            var service = promptReferences.getIfAvailable();
            if (service == null) throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "提示词服务尚未配置");
            return reply.setPage(service.list(call, request)).build();
        } catch (RacingServiceException failure) {
            return reply.setError(failure.error()).build();
        } catch (PromptBusinessException failure) {
            return reply.setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return reply.setError(RacingError.newBuilder().setCode("PERSISTENCE_UNCONFIRMED")
                    .setMessage("提示词操作尚未确认，请重新查询当前状态")).build();
        }
    }

    @Override
    public DeletePromptTargetReply deletePromptVersion(DeletePromptVersionRequest request) {
        var reply = DeletePromptTargetReply.newBuilder();
        try {
            if (request == null || !request.hasContext()) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "提示词请求缺少运行上下文");
            }
            var call = authorization.authorize("DeletePromptVersion", request.getContext());
            reply.setActualTarget(call.actualTarget());
            var service = promptReferences.getIfAvailable();
            if (service == null) throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "提示词服务尚未配置");
            return service.deleteUnused(call, PromptTargetRef.newBuilder().setPromptVersion(request.getVersion()).build());
        } catch (RacingServiceException failure) {
            return reply.setError(failure.error()).build();
        } catch (PromptBusinessException failure) {
            return reply.setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return reply.setError(RacingError.newBuilder().setCode("PERSISTENCE_UNCONFIRMED")
                    .setMessage("提示词操作尚未确认，请重新查询当前状态")).build();
        }
    }

    @Override
    public DeletePromptTargetReply deletePromptSetVersion(DeletePromptSetVersionRequest request) {
        var reply = DeletePromptTargetReply.newBuilder();
        try {
            if (request == null || !request.hasContext()) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "提示词请求缺少运行上下文");
            }
            var call = authorization.authorize("DeletePromptSetVersion", request.getContext());
            reply.setActualTarget(call.actualTarget());
            var service = promptReferences.getIfAvailable();
            if (service == null) throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "提示词服务尚未配置");
            return service.deleteUnused(call, PromptTargetRef.newBuilder().setPromptSetVersion(request.getVersion()).build());
        } catch (RacingServiceException failure) {
            return reply.setError(failure.error()).build();
        } catch (PromptBusinessException failure) {
            return reply.setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return reply.setError(RacingError.newBuilder().setCode("PERSISTENCE_UNCONFIRMED")
                    .setMessage("提示词操作尚未确认，请重新查询当前状态")).build();
        }
    }

    @Override
    public GetModelInputReply getModelInput(GetModelInputRequest request) {
        var reply = GetModelInputReply.newBuilder();
        try {
            if (request == null || !request.hasContext()) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "模型输入查询请求缺少运行上下文");
            }
            var call = authorization.authorize("GetModelInput", request.getContext());
            reply.setActualTarget(call.actualTarget());
            throw RacingServiceException.failure("FORBIDDEN", "模型输入历史读取尚未开放独立授权");
        } catch (RacingServiceException failure) {
            return reply.setError(failure.error()).build();
        }
    }

    @Override
    public ReadAssignmentHistoryReply readAssignmentHistory(ReadAssignmentHistoryRequest request) {
        try {
            if (request == null) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "分配历史请求缺失");
            }
            ExperimentHistoryService service = histories.getIfAvailable();
            if (service == null) {
                throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "实验历史服务尚未配置");
            }
            return ReadAssignmentHistoryReply.newBuilder().setPage(service.readAssignments(request)).build();
        } catch (RacingServiceException failure) {
            return ReadAssignmentHistoryReply.newBuilder().setError(failure.error()).build();
        } catch (ExperimentConfigurationException failure) {
            return ReadAssignmentHistoryReply.newBuilder().setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return ReadAssignmentHistoryReply.newBuilder().setError(RacingError.newBuilder()
                    .setCode("PERSISTENCE_UNCONFIRMED").setMessage("分配历史尚未确认，请用原范围重新查询")).build();
        }
    }

    @Override
    public ReadPromptSetVersionHistoryReply readPromptSetVersionHistory(ReadPromptSetVersionHistoryRequest request) {
        try {
            if (request == null) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "整组历史请求缺失");
            }
            ExperimentHistoryService service = histories.getIfAvailable();
            if (service == null) {
                throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "实验历史服务尚未配置");
            }
            return ReadPromptSetVersionHistoryReply.newBuilder().setVersion(service.readPromptSetVersion(
                    request.getEnvironment(), request.getScopeInstanceId(), request.getPromptSetId(),
                    request.getVersion())).build();
        } catch (RacingServiceException failure) {
            return ReadPromptSetVersionHistoryReply.newBuilder().setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return ReadPromptSetVersionHistoryReply.newBuilder().setError(RacingError.newBuilder()
                    .setCode("PERSISTENCE_UNCONFIRMED").setMessage("整组历史尚未确认，请用原引用重新查询")).build();
        }
    }

    @Override
    public ReadPromptVersionHistoryReply readPromptVersionHistory(ReadPromptVersionHistoryRequest request) {
        try {
            if (request == null || !request.hasVersion()) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "正文历史请求缺少原版本引用");
            }
            ExperimentHistoryService service = histories.getIfAvailable();
            if (service == null) {
                throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "实验历史服务尚未配置");
            }
            return ReadPromptVersionHistoryReply.newBuilder()
                    .setVersion(service.readPromptVersion(request.getVersion())).build();
        } catch (RacingServiceException failure) {
            return ReadPromptVersionHistoryReply.newBuilder().setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return ReadPromptVersionHistoryReply.newBuilder().setError(RacingError.newBuilder()
                    .setCode("PERSISTENCE_UNCONFIRMED").setMessage("正文历史尚未确认，请用原引用重新查询")).build();
        }
    }

    @Override
    public QueryExperimentResultReply queryExperimentResult(QueryExperimentResultRequest request) {
        try {
            if (request == null) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "实验结果查询请求缺失");
            }
            developers.authorize(DeveloperAuthorization.QUERY_EXPERIMENT_RESULT, request.getEnvironment(),
                    request.getScopeInstanceId());
            return QueryExperimentResultReply.newBuilder().setPage(resultService().query(request)).build();
        } catch (RacingServiceException failure) {
            return QueryExperimentResultReply.newBuilder().setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return QueryExperimentResultReply.newBuilder().setError(RacingError.newBuilder()
                    .setCode("PERSISTENCE_UNCONFIRMED").setMessage("实验结果尚未确认，请用原范围重新查询")).build();
        }
    }

    @Override
    public SupplementExperimentResultReply supplementExperimentResult(SupplementExperimentResultRequest request) {
        try {
            if (request == null) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "实验结果补充请求缺失");
            }
            developers.authorize(DeveloperAuthorization.SUPPLEMENT_EXPERIMENT_RESULT, request.getEnvironment(),
                    request.getScopeInstanceId());
            resultService().supplement(request);
            return SupplementExperimentResultReply.newBuilder().setAccepted(ExperimentResultAccepted.getDefaultInstance())
                    .build();
        } catch (RacingServiceException failure) {
            return SupplementExperimentResultReply.newBuilder().setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return SupplementExperimentResultReply.newBuilder().setError(RacingError.newBuilder()
                    .setCode("PERSISTENCE_UNCONFIRMED").setMessage("实验结果补充尚未确认，请用原范围重新查询")).build();
        }
    }

    @Override
    public CorrectExperimentResultReply correctExperimentResult(CorrectExperimentResultRequest request) {
        try {
            if (request == null) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "实验结果修正请求缺失");
            }
            developers.authorize(DeveloperAuthorization.CORRECT_EXPERIMENT_RESULT, request.getEnvironment(),
                    request.getScopeInstanceId());
            resultService().correct(request);
            return CorrectExperimentResultReply.newBuilder().setAccepted(ExperimentResultAccepted.getDefaultInstance())
                    .build();
        } catch (RacingServiceException failure) {
            return CorrectExperimentResultReply.newBuilder().setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return CorrectExperimentResultReply.newBuilder().setError(RacingError.newBuilder()
                    .setCode("PERSISTENCE_UNCONFIRMED").setMessage("实验结果修正尚未确认，请用原范围重新查询")).build();
        }
    }

    @Override
    public DeleteExperimentResultReply deleteExperimentResult(DeleteExperimentResultRequest request) {
        try {
            if (request == null) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "实验结果删除请求缺失");
            }
            developers.authorize(DeveloperAuthorization.DELETE_EXPERIMENT_RESULT, request.getEnvironment(),
                    request.getScopeInstanceId());
            resultService().delete(request);
            return DeleteExperimentResultReply.newBuilder().setAccepted(ExperimentResultAccepted.getDefaultInstance())
                    .build();
        } catch (RacingServiceException failure) {
            return DeleteExperimentResultReply.newBuilder().setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return DeleteExperimentResultReply.newBuilder().setError(RacingError.newBuilder()
                    .setCode("PERSISTENCE_UNCONFIRMED").setMessage("实验结果删除尚未确认，请用原范围重新查询")).build();
        }
    }

    @Override
    public SummarizeExperimentResultReply summarizeExperimentResult(SummarizeExperimentResultRequest request) {
        try {
            if (request == null) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "实验结果汇总请求缺失");
            }
            developers.authorize(DeveloperAuthorization.QUERY_EXPERIMENT_RESULT, request.getEnvironment(),
                    request.getScopeInstanceId());
            return SummarizeExperimentResultReply.newBuilder().setSummary(resultService().summarize(request)).build();
        } catch (RacingServiceException failure) {
            return SummarizeExperimentResultReply.newBuilder().setError(failure.error()).build();
        } catch (DataAccessException | TransactionException failure) {
            return SummarizeExperimentResultReply.newBuilder().setError(RacingError.newBuilder()
                    .setCode("PERSISTENCE_UNCONFIRMED").setMessage("实验结果汇总尚未确认，请用原范围重新查询")).build();
        }
    }

    private ExperimentResultService resultService() {
        ExperimentResultService service = results.getIfAvailable();
        if (service == null) {
            throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "实验结果服务尚未配置");
        }
        return service;
    }
}
