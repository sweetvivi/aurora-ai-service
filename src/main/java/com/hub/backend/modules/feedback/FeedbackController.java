package com.hub.backend.modules.feedback;

import com.hub.backend.modules.auth.CurrentUserResolver;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.feedback.dto.FeedbackRequest;
import com.hub.backend.modules.feedback.dto.FeedbackResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public feedback endpoint behind the sidebar「意见反馈」entry.
 *
 * <p>Anonymous submissions are allowed on purpose: a visitor who cannot sign in is exactly the
 * one who most needs to report something. A token, when present, only adds the account ids.
 */
@RestController
@RequestMapping("/api/v1/feedback")
@Tag(name = "意见反馈", description = "前台反馈页提交入口，登录可选")
public class FeedbackController {

    private final FeedbackService feedbackService;
    private final CurrentUserResolver currentUserResolver;

    public FeedbackController(FeedbackService feedbackService,
                              CurrentUserResolver currentUserResolver) {
        this.feedbackService = feedbackService;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "提交一条反馈")
    @PostMapping
    public ResponseEntity<FeedbackResponse> submit(@Valid @RequestBody FeedbackRequest body,
                                                   HttpServletRequest request) {
        CurrentUser user = currentUserResolver.resolveOptional(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(feedbackService.submit(user, body));
    }
}
