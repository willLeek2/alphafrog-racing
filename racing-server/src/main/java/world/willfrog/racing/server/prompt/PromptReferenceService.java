package world.willfrog.racing.server.prompt;

import world.willfrog.racing.idl.PromptReferenceReplacement;
import world.willfrog.racing.server.security.AuthorizedCall;

/** 单体内部的明确引用维护入口。 */
public interface PromptReferenceService {
    /**
     * 加入调用方事务，替换同一引用方的引用清单。
     * <p>业务先核对权限、范围与修订，并锁定业务主记录。引用维护复用公共授权结果，
     * 按类型、编号和版本排序锁定目标，再检查当前读取授权。相同清单重发复用原结果；
     * 旧修订或同修订不同清单返回错误，空清单也检查修订。</p>
     * <p>草稿使用真实修订，发布版本和结束记录各有独立身份。替换草稿仅解除该草稿的旧引用，
     * 历史记录继续保护正文，删除历史记录时才解除对应引用。根选择和输入使用修订0。</p>
     */
    void replaceForOwner(AuthorizedCall authorizedCall, PromptReferenceReplacement replacement);
}
