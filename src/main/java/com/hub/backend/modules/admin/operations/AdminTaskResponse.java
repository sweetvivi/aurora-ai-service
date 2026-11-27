package com.hub.backend.modules.admin.operations;

import com.hub.backend.modules.aicanvas.entity.AiCanvasTaskEntity;

import java.time.LocalDateTime;

public record AdminTaskResponse(
        String id,
        String username,
        String prompt,
        String model,
        String status,
        LocalDateTime createdAt) {

    public static AdminTaskResponse from(AiCanvasTaskEntity task, String username) {
        String model = task.getModelKey();
        if (model == null || model.isBlank()) {
            model = task.getUpstreamModel();
        }
        return new AdminTaskResponse(
                task.getTaskId(),
                username == null ? "" : username,
                task.getPrompt() == null ? "" : task.getPrompt(),
                model == null ? "" : model,
                frontendStatus(task.getStatus()),
                task.getCreatedAt());
    }

    private static String frontendStatus(String status) {
        if (AiCanvasTaskEntity.STATUS_RUNNING.equals(status)) {
            return "generating";
        }
        if (AiCanvasTaskEntity.STATUS_SUCCEEDED.equals(status)) {
            return "success";
        }
        if (AiCanvasTaskEntity.STATUS_FAILED.equals(status)) {
            return "failed";
        }
        return "pending";
    }
}
