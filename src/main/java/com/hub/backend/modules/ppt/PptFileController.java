package com.hub.backend.modules.ppt;

import com.hub.backend.modules.auth.CurrentUserResolver;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.file.FileService;
import com.hub.backend.modules.file.dto.UploadedFileResponse;
import com.hub.backend.modules.ppt.dto.PptDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * PPT 参考文件上传。底层复用通用 {@link FileService}，响应字段对齐 pptApi.md。
 */
@RestController
@RequestMapping(value = "/api/v1/ppt/files", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "AI PPT-文件", description = "PPT 参考文件上传")
public class PptFileController {

    private final FileService fileService;
    private final CurrentUserResolver currentUserResolver;

    public PptFileController(FileService fileService, CurrentUserResolver currentUserResolver) {
        this.fileService = fileService;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "上传 PPT 参考文件",
            description = "复用通用附件存储；返回的 fileId 可直接放入创建任务的 fileIds。也可改用 POST /api/v1/files。")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PptDtos.UploadedFileResponse upload(@RequestParam("file") MultipartFile file,
                                               HttpServletRequest request) {
        CurrentUser user = currentUserResolver.requireAuthenticated(request);
        UploadedFileResponse uploaded = fileService.upload(user, file, null);
        return new PptDtos.UploadedFileResponse(
                String.valueOf(uploaded.id()),
                uploaded.originalName(),
                uploaded.sizeBytes(),
                uploaded.mimeType());
    }
}
