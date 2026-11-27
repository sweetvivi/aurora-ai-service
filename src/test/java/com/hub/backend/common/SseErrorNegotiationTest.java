package com.hub.backend.common;

import com.hub.backend.common.exception.UnauthorizedException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 流式（SSE）端点上的错误也要说人话。
 *
 * <p>这些端点在 mapping 上写着 {@code produces = text/event-stream}，Spring 会把它记成
 * 本次请求的可写媒体类型，写响应时再与 {@code Accept} 协商。SSE 客户端照规范只发
 * {@code Accept: text/event-stream}，于是异常处理器想写的 JSON 错误体协商不出可接受的类型，
 * 抛 {@code HttpMediaTypeNotAcceptableException}：未登录从「401 + 请先登录」变成
 * 「500 + 空响应体」，App / 小程序 / 第三方对接只能看到一个没有原因的服务器错误。</p>
 *
 * <p>这里用一个最小的「只会写 SSE」的端点复现协商条件，钉住两件事：状态码仍是 401，
 * 且无论客户端 Accept 写什么，响应体都是可解析的 JSON 错误。</p>
 */
class SseErrorNegotiationTest {

    /** 只声明 SSE 的端点：正常路径返回事件流，异常路径由全局处理器接管。 */
    @RestController
    static class StreamingController {

        @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        String stream() {
            throw new UnauthorizedException("请先登录后再试");
        }
    }

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new StreamingController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void sseOnlyAcceptStillGetsReadableJsonError() throws Exception {
        mockMvc.perform(post("/stream").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("请先登录后再试"))
                .andExpect(jsonPath("$.code").value(ErrorCode.AUTH_EXPIRED));
    }

    /** 客户端同时接受两种格式（前端现在的写法）时，不该因为多了 application/json 就变样。 */
    @Test
    void mixedAcceptIsHandledTheSameWay() throws Exception {
        mockMvc.perform(post("/stream")
                        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("请先登录后再试"));
    }

    /** 不带 Accept（curl 默认接受所有类型）这条路径以前就是好的，顺手钉住不回退。 */
    @Test
    void anyAcceptKeepsTheBusinessCopy() throws Exception {
        mockMvc.perform(post("/stream").accept(MediaType.ALL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("请先登录后再试"));
    }
}
