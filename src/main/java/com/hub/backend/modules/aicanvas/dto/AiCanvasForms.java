package com.hub.backend.modules.aicanvas.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Multipart form contracts. Java beans are used here rather than records so Spring MVC and
 * springdoc both expose the same mutable form fields and binary multipart schema.
 */
public final class AiCanvasForms {

    private AiCanvasForms() {
    }

    @Schema(name = "AiCanvasImageGenerationForm", description = "画布图像生成 multipart 表单")
    public static class ImageGenerationForm {

        @NotBlank(message = "prompt must not be blank")
        @Size(max = 4000, message = "prompt must be at most 4000 characters")
        private String prompt;

        @Size(max = 128, message = "imageModel must be at most 128 characters")
        private String imageModel;

        @Size(max = 32, message = "aspectRatio must be at most 32 characters")
        private String aspectRatio;

        @Size(max = 32, message = "resolution must be at most 32 characters")
        private String resolution;

        @ArraySchema(schema = @Schema(type = "string", format = "binary"),
                arraySchema = @Schema(description = "参考图片，可重复提交"))
        private List<MultipartFile> images;

        @Schema(type = "string", format = "binary", description = "单张参考图片")
        private MultipartFile image;

        @Size(max = 16_000_000, message = "imageBase64 is too large")
        @Schema(description = "裸 base64 或 data:<mime>;base64,<data> 格式的参考图")
        private String imageBase64;

        @ArraySchema(schema = @Schema(type = "string", format = "uri"),
                arraySchema = @Schema(description = "参考图片 URL，可重复提交；浏览器无法读取跨域图片时使用"))
        private List<String> referenceUrls;

        @Size(max = 32, message = "source must be at most 32 characters")
        @Schema(description = "产出该图的功能，可选；缺省 canvas。图片生成页复用本接口做图生图时会传 image_generation")
        private String source;

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public String getPrompt() {
            return prompt;
        }

        public void setPrompt(String prompt) {
            this.prompt = prompt;
        }

        public String getImageModel() {
            return imageModel;
        }

        public void setImageModel(String imageModel) {
            this.imageModel = imageModel;
        }

        public String getAspectRatio() {
            return aspectRatio;
        }

        public void setAspectRatio(String aspectRatio) {
            this.aspectRatio = aspectRatio;
        }

        public String getResolution() {
            return resolution;
        }

        public void setResolution(String resolution) {
            this.resolution = resolution;
        }

        public List<MultipartFile> getImages() {
            return images;
        }

        public void setImages(List<MultipartFile> images) {
            this.images = images;
        }

        public MultipartFile getImage() {
            return image;
        }

        public void setImage(MultipartFile image) {
            this.image = image;
        }

        public String getImageBase64() {
            return imageBase64;
        }

        public void setImageBase64(String imageBase64) {
            this.imageBase64 = imageBase64;
        }

        public List<String> getReferenceUrls() {
            return referenceUrls;
        }

        public void setReferenceUrls(List<String> referenceUrls) {
            this.referenceUrls = referenceUrls;
        }
    }

    @Schema(name = "AiCanvasAssetUploadForm", description = "AI 画布素材上传 multipart 表单")
    public static class AssetUploadForm {

        @NotNull(message = "file must not be null")
        @Schema(type = "string", format = "binary", description = "素材文件")
        private MultipartFile file;

        @NotBlank(message = "category must not be blank")
        @Size(max = 32, message = "category must be at most 32 characters")
        private String category;

        @Size(max = 512, message = "name must be at most 512 characters")
        private String name;

        @Size(max = 4000, message = "prompt must be at most 4000 characters")
        private String prompt;

        @Size(max = 128, message = "model must be at most 128 characters")
        private String model;

        public MultipartFile getFile() {
            return file;
        }

        public void setFile(MultipartFile file) {
            this.file = file;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getPrompt() {
            return prompt;
        }

        public void setPrompt(String prompt) {
            this.prompt = prompt;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }
}
