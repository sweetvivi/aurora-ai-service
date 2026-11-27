package com.hub.backend.modules.ppt;

import com.hub.backend.common.config.properties.PptProperties;
import com.hub.backend.infra.pptmaster.PptMasterClient;
import com.hub.backend.modules.ppt.dto.PptDtos.EstimateRequest;
import com.hub.backend.modules.ppt.dto.PptDtos.EstimateResponse;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class PptEstimateService {

    private final PptProperties properties;

    public PptEstimateService(PptProperties properties) {
        this.properties = properties;
    }

    public EstimateResponse estimate(EstimateRequest request) {
        boolean autoImg = request != null && Boolean.TRUE.equals(request.autoImg());
        boolean webSearch = request != null && Boolean.TRUE.equals(request.webSearch());
        String pageRange = request == null ? null : request.pageRange();
        String imgDensity = request == null ? null : request.imgDensity();
        return estimate(pageRange, autoImg, webSearch, imgDensity);
    }

    public EstimateResponse estimate(String pageRange, boolean autoImg, boolean webSearch) {
        return estimate(pageRange, autoImg, webSearch, "mid");
    }

    public EstimateResponse estimate(String pageRange, boolean autoImg, boolean webSearch, String imgDensity) {
        PptProperties.Estimate cfg = properties.getEstimate();
        int slides = PptRequestMapper.resolveSlideCount(pageRange);
        int base = cfg.getBasePoints();
        int perSlide = cfg.getPerSlidePoints() * slides;
        int estimatedImages = autoImg ? estimateImageCount(slides, imgDensity) : 0;
        int img = estimatedImages * cfg.getAutoImgPoints();
        int search = webSearch ? cfg.getWebSearchPoints() : 0;
        int total = Math.max(1, base + perSlide + img + search);

        Map<String, Integer> breakdown = new LinkedHashMap<>();
        breakdown.put("base", base);
        breakdown.put("slides", perSlide);
        breakdown.put("autoImg", img);
        breakdown.put("autoImgCount", estimatedImages);
        breakdown.put("webSearch", search);
        return new EstimateResponse(total, breakdown);
    }

    /**
     * Mid-tier default (~40% of slides). Cover/section bias is applied inside the worker;
     * estimate uses a simple fraction so pricing stays predictable.
     */
    static int estimateImageCount(int slides, String imgDensity) {
        int n = Math.max(1, slides);
        double ratio = switch (PptMasterClient.normalizeDensity(imgDensity)) {
            case "light" -> 0.20;
            case "heavy" -> 0.65;
            default -> 0.40;
        };
        return Math.max(1, (int) Math.ceil(n * ratio));
    }
}
