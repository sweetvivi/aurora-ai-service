package com.hub.backend.modules.agent.repository;

import com.hub.backend.modules.agent.entity.AgentChatMessageEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface AgentChatMessageRepository extends JpaRepository<AgentChatMessageEntity, Long> {

    @Query("""
            select coalesce(max(m.sequenceNo), 0)
              from AgentChatMessageEntity m
             where m.agentConversationId = :conversationId
            """)
    int findMaxSequenceNo(@Param("conversationId") Long conversationId);

    List<AgentChatMessageEntity> findByAgentConversationIdOrderBySequenceNoAsc(Long conversationId);

    /**
     * 最近若干条可用于上下文的已完成消息，按序号倒序取出（调用方自行反转）。
     *
     * <p>限定 {@code COMPLETED}：失败与用户中断的消息正文为空或只有半句话，喂给模型只会
     * 制造幻觉。{@code Pageable} 只用来限制条数，排序由方法名决定。</p>
     *
     * <p>同时限定 {@code active}：编辑或重新生成会留下被软删的旧版本，它们必须留在库里供
     * 切换，但绝不能进模型上下文 —— 否则"编辑"这个动作对模型就是无效的。</p>
     */
    List<AgentChatMessageEntity> findByAgentConversationIdAndActiveTrueAndStatusOrderBySequenceNoDesc(
            Long conversationId, String status, Pageable pageable);

    /** 某个版本组里的全部版本，按版本号升序，用于渲染 {@code 1/N}。 */
    List<AgentChatMessageEntity> findByAgentConversationIdAndTurnGroupIdOrderByVersionAsc(
            Long conversationId, Long turnGroupId);

    @Query("""
            select coalesce(max(m.version), 0)
              from AgentChatMessageEntity m
             where m.agentConversationId = :conversationId
               and m.turnGroupId = :turnGroupId
            """)
    int findMaxVersion(@Param("conversationId") Long conversationId,
                       @Param("turnGroupId") Long turnGroupId);

    /** 回退到某个版本时要用：把该序号及之后的活跃消息全部停用。 */
    List<AgentChatMessageEntity> findByAgentConversationIdAndActiveTrueAndSequenceNoGreaterThanEqualOrderBySequenceNoAsc(
            Long conversationId, int sequenceNo);

    /** 编辑配对切换用：某条 user 消息之下的活跃答案。 */
    List<AgentChatMessageEntity> findByAgentConversationIdAndReplyToMessageIdAndActiveTrueOrderBySequenceNoAsc(
            Long conversationId, Long replyToMessageId);

    /** 切 user 版本时用：指向该组任一版本的全部答案（含非活跃的）。 */
    List<AgentChatMessageEntity> findByAgentConversationIdAndReplyToMessageIdInOrderBySequenceNoAsc(
            Long conversationId, Collection<Long> replyToMessageIds);


    List<AgentChatMessageEntity> findByAgentConversationIdAndIdGreaterThanOrderByIdAsc(
            Long conversationId, Long cursor, Pageable pageable);

    long countByAgentConversationId(Long conversationId);

    /**
     * 每个版本组各有多少个版本，一次查完。
     *
     * <p>历史接口要渲染 {@code 1/N}，逐条查会变成 N+1；这里按组聚合一次即可。</p>
     */
    @Query("""
            select m.turnGroupId, count(m)
              from AgentChatMessageEntity m
             where m.agentConversationId = :conversationId
             group by m.turnGroupId
            """)
    List<Object[]> countVersionsByConversation(@Param("conversationId") Long conversationId);

    /**
     * 引用了某个附件的对话标题。
     *
     * <p>附件只以 {@code attachments_json} 里的 URL 形式存在，没有单独的关联表，所以判断
     * "这张图还在被哪些对话使用"只能按片段匹配（形如 {@code /files/123/content}）。
     * 先限定会话归属，避免把别人的标题当线索返回。</p>
     */
    @Query("""
            select distinct conversation.title
              from AgentChatMessageEntity message, AgentConversationEntity conversation
             where message.agentConversationId = conversation.id
               and conversation.userId = :userId
               and message.attachmentsJson like concat('%', :fragment, '%')
            """)
    List<String> findConversationTitlesUsingAttachment(
            @Param("userId") Long userId, @Param("fragment") String fragment);
}
