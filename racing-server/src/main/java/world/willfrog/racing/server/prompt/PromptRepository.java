package world.willfrog.racing.server.prompt;

import java.util.List;
import world.willfrog.racing.idl.*;

/**
 * 所有写入与目标锁都复用业务事务，数据库实现不自行提交。
 */
public interface PromptRepository {

    PromptRecord prompt(PromptLocator key, boolean lock);

    PromptRecord promptByName(PromptScope scope, String name);

    boolean insertPrompt(PromptRecord value);

    void updatePrompt(PromptRecord value, long revision);

    PromptVersion version(PromptVersionRef key, boolean lock);

    void insertVersion(PromptVersion value);

    void updateAuthorization(PromptVersion value, long revision);

    List<PromptRecord> prompts(PromptScope scope, String name, String purpose, int offset, int limit);

    long promptCount(PromptScope scope, String name, String purpose);

    List<PromptVersion> versions(PromptLocator key, int offset, int limit);

    long versionCount(PromptLocator key);

    PromptSetRecord promptSet(PromptSetLocator key, boolean lock);

    boolean insertSet(PromptSetRecord value);

    void updateSet(PromptSetRecord value, long revision);

    PromptSetVersion setVersion(PromptSetVersionRef key, boolean lock);

    void insertSetVersion(PromptSetVersion value);

    List<PromptSetRecord> sets(PromptScope scope, String name, int offset, int limit);

    long setCount(PromptScope scope, String name);

    List<PromptSetVersion> setVersions(PromptSetLocator key, int offset, int limit);

    long setVersionCount(PromptSetLocator key);

    PromptSetDefault defaultSet(PromptScope scope, String business, boolean lock);

    boolean insertDefaultSet(PromptSetDefault value);

    void updateDefaultSet(PromptSetDefault value, long revision);

    PromptSelection selection(RuntimeCallContext context, String id, String key);

    boolean insertSelection(PromptSelection value, String principal);

    List<PromptReference> ownerReferences(PromptReferenceOwner owner, String principal);

    void deleteOwnerReferences(PromptReferenceOwner owner, String principal);

    void insertReference(PromptReference value, String principal);

    List<PromptReference> references(PromptTargetRef target, int offset, int limit);

    long referenceCount(PromptTargetRef target);

    void deleteVersion(PromptVersionRef target);

    void deleteSetVersion(PromptSetVersionRef target);

    void deleteSetMemberReferences(PromptSetVersionRef target);
}
