package com.hub.backend.modules.agent.tool;

import com.hub.backend.infra.llm.dto.LlmImageAttachment;
import com.hub.backend.modules.auth.dto.CurrentUser;

import java.util.List;

/**
 * 一次工具执行能看到的东西。
 *
 * <p>为什么不是直接传 {@link CurrentUser}：电商类工具（换背景、试穿、换模特）必须拿到
 * <b>用户这一轮上传的参考图</b>才能干活，而图并不在模型给的 JSON 参数里 —— 模型也拿不到
 * 附件 ID。图片由服务端从附件表读出来放进上下文，工具按「第 1 张 / 第 2 张」的顺序使用。</p>
 *
 * <p>模型对话的豆包式改图不依赖本轮上传：服务端把会话「焦点图」放进 {@link #focusImages}，
 * {@code edit_image} 用它做唯一参考图。</p>
 *
 * @param currentUser    调用者，决定站点、配额与文件归属校验
 * @param images         本轮用户消息携带的图片，按上传顺序；没有图时是空列表
 * @param conversationId 当前会话，生成文件落盘时可挂到该会话；可为 null
 * @param imageModel     用户/会话偏好的 IMAGE 模型 key；可空（走站点默认）
 * @param imageCount     文生图张数 1–4；可空（默认 1）
 * @param focusImages    改图用的焦点参考图（本期 0 或 1 张）
 * @param fileSource     落库 {@code source}；可空时由各工具自定（如 agent）
 * @param imageSize      文生图尺寸 WxH / auto；可空（由工具或上游默认）
 */
public record ToolContext(
        CurrentUser currentUser,
        List<LlmImageAttachment> images,
        Long conversationId,
        String imageModel,
        Integer imageCount,
        List<LlmImageAttachment> focusImages,
        String fileSource,
        String imageSize
) {

    public ToolContext {
        images = images == null ? List.of() : List.copyOf(images);
        focusImages = focusImages == null ? List.of() : List.copyOf(focusImages);
        imageModel = imageModel == null || imageModel.isBlank() ? null : imageModel.strip();
        fileSource = fileSource == null || fileSource.isBlank() ? null : fileSource.strip();
        imageSize = imageSize == null || imageSize.isBlank() ? null : imageSize.strip();
    }

    /** 兼容旧调用：无会话 ID。 */
    public ToolContext(CurrentUser currentUser, List<LlmImageAttachment> images) {
        this(currentUser, images, null, null, null, List.of(), null, null);
    }

    /** 兼容：只有会话 ID。 */
    public ToolContext(CurrentUser currentUser, List<LlmImageAttachment> images, Long conversationId) {
        this(currentUser, images, conversationId, null, null, List.of(), null, null);
    }

    /** 不需要图片的工具用这个入口，省得每处都写 {@code List.of()} */
    public static ToolContext of(CurrentUser currentUser) {
        return new ToolContext(currentUser, List.of(), null, null, null, List.of(), null, null);
    }

    public boolean hasImages() {
        return !images.isEmpty();
    }

    public boolean hasFocusImages() {
        return !focusImages.isEmpty();
    }

    /** 改图优先用焦点图；没有焦点时退回本轮上传图。 */
    public List<LlmImageAttachment> referenceImagesForEdit() {
        if (!focusImages.isEmpty()) {
            return focusImages;
        }
        return images;
    }
}
