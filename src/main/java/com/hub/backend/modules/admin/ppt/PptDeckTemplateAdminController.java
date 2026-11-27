package com.hub.backend.modules.admin.ppt;

import com.hub.backend.common.logging.audit.AuditLog;
import com.hub.backend.modules.admin.provider.AdminGuard;
import com.hub.backend.modules.ppt.PptDeckTemplateService;
import com.hub.backend.modules.ppt.dto.PptDeckAdminDtos;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

// 类级默认：本控制器所有写接口都进审计（GET 由切面跳过），个别接口在方法上覆盖 action/id。
@AuditLog(target = "PPT_DECK_TEMPLATE")
@RestController
@RequestMapping("/api/admin/v1/ppt/deck-templates")
public class PptDeckTemplateAdminController {

    private final PptDeckTemplateService service;
    private final AdminGuard guard;

    public PptDeckTemplateAdminController(PptDeckTemplateService service, AdminGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    @GetMapping
    public List<PptDeckAdminDtos.Response> list(HttpServletRequest request) {
        guard.require(request);
        return service.adminList();
    }

    @PostMapping
    public ResponseEntity<PptDeckAdminDtos.Response> create(@Valid @RequestBody PptDeckAdminDtos.Request body,
                                                            HttpServletRequest request) {
        guard.require(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(body));
    }

    @PutMapping("/{id}")
    public PptDeckAdminDtos.Response update(@PathVariable Long id,
                                            @Valid @RequestBody PptDeckAdminDtos.Request body,
                                            HttpServletRequest request) {
        guard.require(request);
        return service.update(id, body);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        guard.require(request);
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/upload-source", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PptDeckAdminDtos.UploadSourceResponse uploadSource(@RequestParam("file") MultipartFile file,
                                                              HttpServletRequest request) {
        guard.require(request);
        return service.uploadSource(file);
    }
}
