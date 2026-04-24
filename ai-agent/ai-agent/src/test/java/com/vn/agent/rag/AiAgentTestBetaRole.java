package com.vn.agent.rag;

import io.jmix.security.role.annotation.ResourceRole;

/**
 * Test-only resource role used by Phase 5 RAG integration tests as a distinct, registered
 * role code that users do NOT hold. Scoped to the test classpath — shipped role set is
 * unaffected.
 */
@ResourceRole(name = "AI Agent Test Beta", code = AiAgentTestBetaRole.CODE)
public interface AiAgentTestBetaRole {
    String CODE = "ai-agent-test-beta";
}
