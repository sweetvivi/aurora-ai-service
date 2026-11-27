package com.hub.backend.modules.ppt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.infra.pptmaster.DeckWorkspaceResolver;
import com.hub.backend.infra.storage.FileStorage;
import com.hub.backend.infra.storage.StoredObject;
import com.hub.backend.modules.ppt.dto.PptDeckAdminDtos;
import com.hub.backend.modules.ppt.dto.PptDtos;
import com.hub.backend.modules.ppt.entity.PptDeckTemplateEntity;
import com.hub.backend.modules.ppt.repository.PptDeckTemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Service
public class PptDeckTemplateService {

    private final PptDeckTemplateRepository repository;
    private final DeckWorkspaceResolver deckWorkspaceResolver;
    private final FileStorage fileStorage;
    private final ObjectMapper objectMapper;

    public PptDeckTemplateService(PptDeckTemplateRepository repository,
                                  DeckWorkspaceResolver deckWorkspaceResolver,
                                  FileStorage fileStorage,
                                  ObjectMapper objectMapper) {
        this.repository = repository;
        this.deckWorkspaceResolver = deckWorkspaceResolver;
        this.fileStorage = fileStorage;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<PptDtos.DeckTemplateResponse> listPublished() {
        return repository.findByStatusOrderBySortOrderAscIdAsc(PptDeckTemplateEntity.STATUS_PUBLISHED)
                .stream()
                .map(e -> toPublic(e, false))
                .toList();
    }

    @Transactional(readOnly = true)
    public PptDeckTemplateEntity requirePublished(String code) {
        PptDeckTemplateEntity entity = repository.findByCode(code == null ? "" : code.trim())
                .orElseThrow(() -> new NotFoundException("Deck 模板不存在: " + code));
        if (entity.getStatus() != PptDeckTemplateEntity.STATUS_PUBLISHED) {
            throw new InvalidRequestException("Deck 模板未上架: " + code);
        }
        // Validate workspace exists on disk
        deckWorkspaceResolver.resolve(entity.getCode());
        return entity;
    }

    @Transactional(readOnly = true)
    public List<PptDeckAdminDtos.Response> adminList() {
        return repository.findAllByOrderBySortOrderAscIdAsc().stream().map(this::toAdmin).toList();
    }

    @Transactional
    public PptDeckAdminDtos.Response create(PptDeckAdminDtos.Request body) {
        String code = body.code().trim();
        if (repository.findByCode(code).isPresent()) {
            throw new InvalidRequestException("Deck code 已存在: " + code);
        }
        PptDeckTemplateEntity entity = new PptDeckTemplateEntity(code, body.name().trim());
        apply(entity, body);
        return toAdmin(repository.save(entity));
    }

    @Transactional
    public PptDeckAdminDtos.Response update(Long id, PptDeckAdminDtos.Request body) {
        PptDeckTemplateEntity entity = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Deck 模板不存在"));
        if (!entity.getCode().equals(body.code().trim()) && repository.findByCode(body.code().trim()).isPresent()) {
            throw new InvalidRequestException("Deck code 已存在: " + body.code());
        }
        entity.setCode(body.code().trim());
        entity.setName(body.name().trim());
        apply(entity, body);
        return toAdmin(repository.save(entity));
    }

    @Transactional
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new NotFoundException("Deck 模板不存在");
        }
        repository.deleteById(id);
    }

    @Transactional
    public PptDeckAdminDtos.UploadSourceResponse uploadSource(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidRequestException("请上传文件");
        }
        try {
            StoredObject stored = fileStorage.store(file.getInputStream(),
                    guessExt(file.getOriginalFilename()));
            return new PptDeckAdminDtos.UploadSourceResponse(stored.storageKey(), stored.storageKey());
        } catch (Exception e) {
            throw new InvalidRequestException("上传失败，请稍后重试");
        }
    }

    private void apply(PptDeckTemplateEntity entity, PptDeckAdminDtos.Request body) {
        entity.setCategory(body.category());
        entity.setTagsJson(writeTags(body.tags()));
        entity.setStyleBlurb(body.styleBlurb());
        entity.setCoverUrl(body.coverUrl());
        entity.setAspectRatio(body.aspectRatio());
        if (body.pageMin() != null) {
            entity.setPageMin(body.pageMin());
        }
        if (body.pageMax() != null) {
            entity.setPageMax(body.pageMax());
        }
        if (body.pageDefault() != null) {
            entity.setPageDefault(body.pageDefault());
        }
        if (StringUtils.hasText(body.renderer())) {
            entity.setRenderer(body.renderer());
        }
        if (StringUtils.hasText(body.themeTemplateId())) {
            entity.setThemeTemplateId(body.themeTemplateId());
        }
        entity.setSpecJson(body.specJson());
        if (body.sourcePptxKey() != null) {
            entity.setSourcePptxKey(body.sourcePptxKey());
        }
        if (body.licenseSource() != null) {
            entity.setLicenseSource(body.licenseSource());
        }
        if (body.commercialOk() != null) {
            entity.setCommercialOk(body.commercialOk());
        }
        if (body.sort() != null) {
            entity.setSortOrder(body.sort());
        }
        if (body.status() != null) {
            entity.setStatus(body.status());
        }
    }

    private PptDtos.DeckTemplateResponse toPublic(PptDeckTemplateEntity e, boolean includeSpec) {
        return new PptDtos.DeckTemplateResponse(
                e.getId(), e.getCode(), e.getName(), e.getCategory(), readTags(e.getTagsJson()),
                e.getStyleBlurb(), e.getCoverUrl(), e.getAspectRatio(),
                e.getPageMin(), e.getPageMax(), e.getPageDefault(),
                e.getRenderer(), e.getThemeTemplateId(),
                includeSpec ? e.getSpecJson() : null,
                e.isCommercialOk(), e.getStatus(), e.getSortOrder());
    }

    private PptDeckAdminDtos.Response toAdmin(PptDeckTemplateEntity e) {
        return new PptDeckAdminDtos.Response(
                e.getId(), e.getCode(), e.getName(), e.getCategory(), readTags(e.getTagsJson()),
                e.getStyleBlurb(), e.getCoverUrl(), e.getAspectRatio(),
                e.getPageMin(), e.getPageMax(), e.getPageDefault(),
                e.getRenderer(), e.getThemeTemplateId(), e.getSpecJson(),
                e.getSourcePptxKey(), e.getLicenseSource(), e.isCommercialOk(),
                e.getSortOrder(), e.getStatus());
    }

    private List<String> readTags(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private String writeTags(List<String> tags) {
        try {
            return objectMapper.writeValueAsString(tags == null ? List.of() : tags);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static String guessExt(String name) {
        if (!StringUtils.hasText(name) || !name.contains(".")) {
            return "bin";
        }
        return name.substring(name.lastIndexOf('.') + 1).toLowerCase();
    }
}
