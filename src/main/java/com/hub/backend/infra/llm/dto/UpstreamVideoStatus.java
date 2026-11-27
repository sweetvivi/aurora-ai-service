package com.hub.backend.infra.llm.dto;

/**
 * Polled state of one upstream video job.
 *
 * <p>Gateways disagree on vocabulary ({@code queued}/{@code pending}, {@code succeeded}/
 * {@code completed}, {@code failed}/{@code error}), so the adapter normalises into {@link State}
 * before this record is built.</p>
 *
 * @param upstreamTaskId supplier side id, echoed back for logs
 * @param state          normalised job state
 * @param progress       percentage 0-100, {@code -1} when the supplier does not report one
 * @param videoUrl       result URL, only set for {@link State#SUCCEEDED}
 * @param errorMessage   failure reason, only set for {@link State#FAILED}
 */
public record UpstreamVideoStatus(String upstreamTaskId, State state, int progress,
                                  String videoUrl, String errorMessage) {

    public enum State {
        QUEUED,
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    public static UpstreamVideoStatus queued(String upstreamTaskId, int progress) {
        return new UpstreamVideoStatus(upstreamTaskId, State.QUEUED, progress, null, null);
    }

    public static UpstreamVideoStatus running(String upstreamTaskId, int progress) {
        return new UpstreamVideoStatus(upstreamTaskId, State.RUNNING, progress, null, null);
    }

    public static UpstreamVideoStatus succeeded(String upstreamTaskId, String videoUrl) {
        return new UpstreamVideoStatus(upstreamTaskId, State.SUCCEEDED, 100, videoUrl, null);
    }

    public static UpstreamVideoStatus failed(String upstreamTaskId, String errorMessage) {
        return new UpstreamVideoStatus(upstreamTaskId, State.FAILED, 0, null, errorMessage);
    }
}
