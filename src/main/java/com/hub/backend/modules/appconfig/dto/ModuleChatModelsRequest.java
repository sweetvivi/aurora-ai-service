package com.hub.backend.modules.appconfig.dto;

import jakarta.validation.constraints.Size;

import java.util.Map;

public record ModuleChatModelsRequest(Map<String, @Size(max = 128) String> models) {
}
