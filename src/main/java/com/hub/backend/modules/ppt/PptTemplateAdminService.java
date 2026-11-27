package com.hub.backend.modules.ppt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.infra.storage.FileStorage;
import com.hub.backend.infra.storage.StoredObject;
import com.hub.backend.modules.ppt.dto.PptTemplateAdminDtos;
import com.hub.backend.modules.ppt.entity.PptTemplateEntity;
import com.hub.backend.modules.ppt.repository.PptTemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

@Service
public class PptTemplateAdminService {
    private static final Set<String> RENDERER_TEMPLATES = Set.of(
            "business-clean", "dark-tech", "fresh-modern",
            "green-fresh-courseware", "blue-business-text");
    private final PptTemplateRepository repository; private final FileStorage storage; private final ObjectMapper mapper;
    public PptTemplateAdminService(PptTemplateRepository repository, FileStorage storage, ObjectMapper mapper) { this.repository=repository; this.storage=storage; this.mapper=mapper; }
    @Transactional(readOnly=true) public List<PptTemplateAdminDtos.Response> list(){ return repository.findAllByOrderByUpdatedAtDesc().stream().map(this::response).toList(); }
    @Transactional public PptTemplateAdminDtos.Response create(PptTemplateAdminDtos.Request r){
        if(repository.findByTemplateIdIgnoreCase(r.templateId().trim()).isPresent()) throw new IllegalArgumentException("模板 ID 已存在: "+r.templateId());
        PptTemplateEntity e=new PptTemplateEntity(r.templateId().trim(),r.name().trim(),r.category().trim()); apply(e,r); return response(repository.save(e));
    }
    @Transactional public PptTemplateAdminDtos.Response update(String id,PptTemplateAdminDtos.Request r){ PptTemplateEntity e=require(id); apply(e,r); return response(repository.save(e)); }
    @Transactional public void delete(String id){ PptTemplateEntity e=require(id); e.setStatus(PptTemplateEntity.DISABLED); repository.save(e); }
    @Transactional public PptTemplateAdminDtos.Response upload(String id, MultipartFile file){
        if(file==null||file.isEmpty()) throw new IllegalArgumentException("PPTX 文件不能为空");
        String name=Optional.ofNullable(file.getOriginalFilename()).orElse("template.pptx");
        if(!name.toLowerCase(Locale.ROOT).endsWith(".pptx")) throw new IllegalArgumentException("只支持 .pptx 模板");
        try { StoredObject stored=storage.store(file.getInputStream(),"pptx"); PptTemplateEntity e=require(id); e.setSourceStorageKey(stored.storageKey()); e.setSourceFileName(name); return response(repository.save(e)); } catch(IOException ex){ throw new IllegalArgumentException("读取模板文件失败",ex); }
    }
    private PptTemplateEntity require(String id){ return repository.findByTemplateIdIgnoreCase(id).orElseThrow(()->new NotFoundException("模板不存在: "+id)); }
    private void apply(PptTemplateEntity e,PptTemplateAdminDtos.Request r){
        e.setName(r.name().trim()); e.setCategory(r.category().trim()); e.setDescription(r.description());
        e.setScenesJson(json(r.scenes())); e.setLayoutsJson(json(r.layouts())); e.setThemeJson(r.themeJson()); e.setPreviewUrl(r.previewUrl());
        if (PptTemplateEntity.PUBLISHED.equals(r.status()) && !RENDERER_TEMPLATES.contains(e.getTemplateId().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("该模板尚未绑定 ppt-master 渲染主题，请先保存为草稿");
        }
        if(r.status()!=null) e.setStatus(r.status());
    }
    private String json(Object value){ try{return value==null?null:mapper.writeValueAsString(value);}catch(JsonProcessingException ex){throw new IllegalArgumentException("模板配置不是有效 JSON",ex);} }
    private PptTemplateAdminDtos.Response response(PptTemplateEntity e){ return new PptTemplateAdminDtos.Response(e.getId(),e.getTemplateId(),e.getName(),e.getCategory(),e.getDescription(),parse(e.getScenesJson()),parse(e.getLayoutsJson()),e.getThemeJson(),e.getSourceFileName(),e.getPreviewUrl(),e.getStatus(),e.getUpdatedAt()); }
    private List<String> parse(String raw){ try{return raw==null?List.of():mapper.readValue(raw,new TypeReference<>(){});}catch(Exception ex){return List.of();} }
}
