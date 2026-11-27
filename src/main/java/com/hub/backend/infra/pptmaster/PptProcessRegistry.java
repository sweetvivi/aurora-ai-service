package com.hub.backend.infra.pptmaster;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks running Agent/worker OS processes so cancel can destroy them.
 */
@Component
public class PptProcessRegistry {

    private final ConcurrentHashMap<String, Process> running = new ConcurrentHashMap<>();

    public void register(String jobId, Process process) {
        if (jobId == null || process == null) {
            return;
        }
        Process previous = running.put(jobId, process);
        if (previous != null && previous.isAlive()) {
            previous.destroyForcibly();
        }
    }

    public void unregister(String jobId) {
        if (jobId != null) {
            running.remove(jobId);
        }
    }

    public boolean cancel(String jobId) {
        Process process = running.remove(jobId);
        if (process == null) {
            return false;
        }
        if (process.isAlive()) {
            process.destroyForcibly();
            return true;
        }
        return false;
    }
}
