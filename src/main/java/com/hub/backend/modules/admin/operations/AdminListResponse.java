package com.hub.backend.modules.admin.operations;

import java.util.List;

public record AdminListResponse<T>(List<T> list, long total) {
}
