package com.hub.backend.modules.support;

import com.hub.backend.modules.agent.entity.AgentEntity;

public final class SupportConstants {

    public static final String AGENT_SN = AgentEntity.SN_SUPPORT_BOT;
    public static final String AGENT_KEY = AgentEntity.KEY_SUPPORT_BOT;

    public static final String ONLINE_ALWAYS = "ALWAYS";
    public static final String ONLINE_SCHEDULED = "SCHEDULED";

    public static final int KNOWLEDGE_TOP_K = 5;
    public static final int KNOWLEDGE_MAX_CHARS = 6000;
    public static final int CHUNK_SIZE = 800;
    public static final int TRANSCRIPT_MAX_MESSAGES = 30;
    public static final int TRANSCRIPT_MAX_CHARS = 8000;

    private SupportConstants() {
    }
}
