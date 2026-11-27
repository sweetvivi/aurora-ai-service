package com.hub.backend.modules.admin.ppt;

import com.hub.backend.common.logging.audit.AuditLog;
import com.hub.backend.modules.admin.provider.AdminGuard;
import com.hub.backend.modules.ppt.PptTemplateAdminService;
import com.hub.backend.modules.ppt.dto.PptTemplateAdminDtos;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

// 类级默认：本控制器所有写接口都进审计（GET 由切面跳过），个别接口在方法上覆盖 action/id。
@AuditLog(target = "PPT_TEMPLATE")
@RestController
@RequestMapping("/api/admin/v1/ppt/templates")
public class PptTemplateAdminController {
    private final PptTemplateAdminService service; private final AdminGuard guard;
    public PptTemplateAdminController(PptTemplateAdminService service,AdminGuard guard){this.service=service;this.guard=guard;}
    @GetMapping public List<PptTemplateAdminDtos.Response> list(HttpServletRequest r){guard.require(r);return service.list();}
    @PostMapping public ResponseEntity<PptTemplateAdminDtos.Response> create(@Valid @RequestBody PptTemplateAdminDtos.Request body,HttpServletRequest r){guard.require(r);return ResponseEntity.status(HttpStatus.CREATED).body(service.create(body));}
    @PutMapping("/{id}") public PptTemplateAdminDtos.Response update(@PathVariable String id,@Valid @RequestBody PptTemplateAdminDtos.Request body,HttpServletRequest r){guard.require(r);return service.update(id,body);}
    @PostMapping(value="/{id}/source",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public PptTemplateAdminDtos.Response upload(@PathVariable String id,@RequestParam("file") MultipartFile file,HttpServletRequest r){guard.require(r);return service.upload(id,file);}
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable String id,HttpServletRequest r){guard.require(r);service.delete(id);return ResponseEntity.noContent().build();}
}
