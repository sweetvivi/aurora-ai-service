package com.hub.backend.modules.support.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SupportHandoffRequest(
        @NotBlank(message = "请填写转人工说明")
        @Size(max = 2000, message = "转人工说明过长")
        String content,

        @Size(max = 120, message = "联系方式过长")
        String contact,

        Long agentConversationId,

        @Size(max = 512, message = "页面地址过长")
        String pageUrl
) {
}
