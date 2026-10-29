package world.willfrog.racing.server.rpc;

import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import world.willfrog.racing.idl.*;
import world.willfrog.racing.server.configuration.ExperimentConfigurationService;
import world.willfrog.racing.server.configuration.ExperimentConfigurationException;
import world.willfrog.racing.server.prompt.PromptBusinessException;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import world.willfrog.racing.server.evaluation.ExperimentEvaluationService;
import world.willfrog.racing.server.input.ModelInputService;
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

    public RacingServiceImpl(ServiceAuthorization authorization,
            ObjectProvider<ExperimentEvaluationService> evaluations,
            ObjectProvider<ExperimentConfigurationService> configurations,
            ObjectProvider<ModelInputService> modelInputs) {
        this.authorization = authorization;
        this.evaluations = evaluations;
        this.configurations = configurations;
        this.modelInputs = modelInputs;
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
}
