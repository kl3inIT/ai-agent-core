package com.vn.agent.entity;

import io.jmix.core.DeletePolicy;
import io.jmix.core.entity.annotation.JmixGeneratedValue;
import io.jmix.core.entity.annotation.OnDelete;
import io.jmix.core.metamodel.annotation.DependsOnProperties;
import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import io.jmix.core.metamodel.annotation.Store;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.UUID;

@Store(name = "agentstore")
@JmixEntity
@Entity(name = "ai_AiMessage")
@Table(name = "AI_AGENT_MESSAGE", indexes = {
        @Index(name = "IDX_AI_AGENT_MESSAGE__ON_CONVERSATION", columnList = "CONVERSATION_ID")
})
public class AiMessage {

    @Id
    @Column(name = "ID", nullable = false)
    @JmixGeneratedValue
    private UUID id;

    @Version
    @Column(name = "VERSION", nullable = false)
    private Integer version;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(DeletePolicy.CASCADE)
    @JoinColumn(name = "CONVERSATION_ID", nullable = false)
    private AiConversation conversation;

    @Column(name = "ROLE_", length = 16)
    private String role;

    @Lob
    @Column(name = "CONTENT")
    private String content;

    @Column(name = "SEQ")
    private Integer seq;

    @Column(name = "CREATED_DATE")
    private OffsetDateTime createdDate;

    @InstanceName
    @DependsOnProperties({"role", "createdDate"})
    public String getDisplayName() {
        return String.format("%s @ %s", role, createdDate);
    }

    public AiMessageRole getRole() { return role == null ? null : AiMessageRole.fromId(role); }
    public void setRole(AiMessageRole role) { this.role = role == null ? null : role.getId(); }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public AiConversation getConversation() { return conversation; }
    public void setConversation(AiConversation conversation) { this.conversation = conversation; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Integer getSeq() { return seq; }
    public void setSeq(Integer seq) { this.seq = seq; }
    public OffsetDateTime getCreatedDate() { return createdDate; }
    public void setCreatedDate(OffsetDateTime createdDate) { this.createdDate = createdDate; }
}
