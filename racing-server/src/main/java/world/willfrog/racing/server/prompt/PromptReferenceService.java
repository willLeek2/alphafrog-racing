package world.willfrog.racing.server.prompt;

import world.willfrog.racing.idl.PromptReferenceReplacement;
import world.willfrog.racing.server.security.AuthorizedCall;

/** 单体内部的明确引用维护入口，不能作为独立远程写接口暴露。 */
public interface PromptReferenceService {
    /**
     * 加入业务已经开启的事务，替换一个引用方的清单。
     *
     * <p>业务先核对权限、范围及自身修改序号，并锁定所属业务主记录。引用维护核对
     * 公共授权结果中的上下文与引用方范围，复用其中的共享来源权限，按类型、编号和版本
     * 排序锁定目标，再检查版本当前读取授权；不为内部登记另行申请远程动作权限。
     * 清单相同的重发复用，旧修订及同修订不同清单拒绝；空清单也遵守业务修订检查。
     * 不解析候选JSON，也不建立新事务或调用外部服务。</p>
     *
     * <p>草稿固定身份使用真实草稿修订；每个不可变发布版本及结束记录使用独立身份。
     * 替换草稿只解除该草稿旧引用，留存发布版本和结束记录继续保护正文。业务删除历史时
     * 才在同一事务解除对应引用。根选择和输入没有可变业务修订时使用0，不伪造配置版本。</p>
     */
    void replaceForOwner(AuthorizedCall authorizedCall, PromptReferenceReplacement replacement);
}
