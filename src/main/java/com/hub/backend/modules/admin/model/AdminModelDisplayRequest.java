package com.hub.backend.modules.admin.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(name = "AdminModelDisplayRequest",
        description = "修改模型的展示字段与本地能力标记，不影响上游模型名和路由")
public record AdminModelDisplayRequest(
        @Schema(description = "展示名称") @Size(min = 1, max = 128) String modelName,
        @Schema(description = "厂商") @Size(max = 128) String company,
        @Schema(description = "图标地址") @Size(max = 1024) String iconUrl,
        @Schema(description = "模型说明") @Size(max = 1024) String description,
        @Schema(description = "排序号") @Min(value = 0, message = "sortOrder must not be negative")
        Integer sortOrder,
        @Schema(description = "是否声明支持思考模式；前台开关还需配置 thinkingParameter",
                example = "true")
        Boolean thinking,
        @Schema(description = "开启思考时写入上游请求的字段名，例如 enable_thinking；空字符串清除配置",
                example = "enable_thinking")
        @Size(max = 64) String thinkingParameter,
        @Schema(description = "开启思考时写入上游的值；空字符串恢复默认 true。DeepSeek 填 true，GPT 可填 medium",
                example = "true")
        @Size(max = 64) String thinkingOnValue,
        @Schema(description = "关闭思考时写入上游的值；空字符串恢复默认 false。DeepSeek 填 false，GPT 可填 none",
                example = "false")
        @Size(max = 64) String thinkingOffValue,
        @Schema(description = "是否支持上游原生联网搜索（tools type=web_search）；前台联网开关据此灰掉",
                example = "true")
        Boolean supportsWebSearch,
        @Schema(description = "模型类型；同步默认保留运营已改值，仅配置 override 会再次覆盖",
                example = "VIDEO")
        @Pattern(regexp = "CHAT|IMAGE|VIDEO|AUDIO",
                message = "modelType must be CHAT, IMAGE, VIDEO or AUDIO")
        String modelType,
        @Schema(description = "视频模型能力声明；仅 VIDEO 类型可写，省略表示不改现有视频能力")
        @Valid AdminVideoCapabilitiesRequest videoCapabilities
) {
}
