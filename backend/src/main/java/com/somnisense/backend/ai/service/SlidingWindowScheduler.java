package com.somnisense.backend.ai.service;

import com.somnisense.backend.ai.config.AiProperties;
import com.somnisense.backend.ai.config.AiSchedulerProperties;
import com.somnisense.backend.prediction.entity.InferenceJobStatus;
import com.somnisense.backend.prediction.entity.InferenceWindowJob;
import com.somnisense.backend.prediction.repository.InferenceWindowJobRepository;
import com.somnisense.backend.prediction.repository.PredictionRepository;
import com.somnisense.backend.prediction.service.InferenceJobService;
import com.somnisense.backend.session.entity.MonitoringSession;
import com.somnisense.backend.session.entity.SessionStatus;
import com.somnisense.backend.session.repository.MonitoringSessionRepository;
import com.somnisense.backend.simulation.service.SimulationLifecycleService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Continuous sliding-window Model C evaluation scheduler & bounded retry dispatcher (Stage 4B).
 *
 * Guarantees:
 * - 60s evaluation windows sliding every 10s with 360s causal context and configurable grace lag (3s).
 * - Durable cursor derived per session & model version from max(predictions, inference_window_jobs) + 10s.
 * - Independent processing of due RETRY_WAIT jobs with 10s/30s/90s backoff and 4-attempt cap.
 * - Round-robin fairness with a 6-window-per-session cap per tick across bounded worker threads.
 * - Per-session ReentrantLock prevents concurrent evaluation within the same session while allowing
 *   distinct sessions to evaluate in parallel. External HTTP calls execute strictly outside DB locks.
 */
@Service
public class SlidingWindowScheduler {

    private static final Logger log = LoggerFactory.getLogger(SlidingWindowScheduler.class);
    private static final int RETRY_BATCH_LIMIT = 100;

    private final MonitoringSessionRepository sessionRepository;
    private final PredictionRepository predictionRepository;
    private final InferenceWindowJobRepository jobRepository;
    private final InferenceJobService inferenceJobService;
    private final WindowEvaluationService windowEvaluationService;
    private final SimulationLifecycleService simulationLifecycleService;
    private final AiProperties aiProperties;
    private final AiSchedulerProperties schedulerProperties;

    private final Map<UUID, ReentrantLock> sessionLocks = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicBoolean tickRunning = new AtomicBoolean(false);
    private final AtomicInteger roundRobinCursor = new AtomicInteger(0);
    private final ExecutorService executor;

    public SlidingWindowScheduler(MonitoringSessionRepository sessionRepository,
                                  PredictionRepository predictionRepository,
                                  InferenceWindowJobRepository jobRepository,
                                  InferenceJobService inferenceJobService,
                                  WindowEvaluationService windowEvaluationService,
                                  SimulationLifecycleService simulationLifecycleService,
                                  AiProperties aiProperties,
                                  AiSchedulerProperties schedulerProperties) {
        this.sessionRepository = sessionRepository;
        this.predictionRepository = predictionRepository;
        this.jobRepository = jobRepository;
        this.inferenceJobService = inferenceJobService;
        this.windowEvaluationService = windowEvaluationService;
        this.simulationLifecycleService = simulationLifecycleService;
        this.aiProperties = aiProperties;
        this.schedulerProperties = schedulerProperties;

        int threads = Math.max(1, schedulerProperties.getWorkerThreads());
        int queueCapacity = Math.max(32, threads * 32);
        AtomicInteger threadNum = new AtomicInteger(1);
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "ai-window-worker-" + threadNum.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
        this.executor = new ThreadPoolExecutor(
                threads,
                threads,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                tf,
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    /** Execution summary of a single scheduler tick. */
    public record TickSummary(
            boolean skippedOverlap,
            int sessionsScanned,
            int retriesAttempted,
            int windowsEvaluated,
            int failuresRecorded,
            int cancelledCleaned
    ) {
        public static TickSummary skipped() {
            return new TickSummary(true, 0, 0, 0, 0, 0);
        }
    }

    private record WindowTask(UUID sessionId, Instant windowStart, Instant windowEnd, boolean retry) {
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    @Scheduled(fixedDelayString = "${somnisense.ai.scheduler.fixed-delay-ms:2000}")
    public void scheduledTick() {
        if (!schedulerProperties.isEnabled()) {
            return;
        }
        tickAt(Instant.now());
    }

    public TickSummary tick() {
        return tickAt(Instant.now());
    }

    /** Executes one bounded round-robin tick using the supplied clock instant. */
    public TickSummary tickAt(Instant now) {
        if (!tickRunning.compareAndSet(false, true)) {
            return TickSummary.skipped();
        }
        try {
            try {
                simulationLifecycleService.expireStaleLeases();
            } catch (RuntimeException ex) {
                log.warn("Best-effort stale simulator lease sweep failed: {}", ex.getMessage());
            }

            String modelVersion = aiProperties.getModelVersion();
            AtomicInteger cancelledCleaned = new AtomicInteger(0);
            AtomicInteger retriesAttempted = new AtomicInteger(0);
            AtomicInteger windowsEvaluated = new AtomicInteger(0);
            AtomicInteger failuresRecorded = new AtomicInteger(0);
            Set<UUID> haltedSessionsThisTick = ConcurrentHashMap.newKeySet();

            // 1. Due retry jobs (processed independently of the forward cursor)
            List<InferenceWindowJob> dueRetries = jobRepository
                    .findByStatusAndNextRetryAtLessThanEqualOrderByNextRetryAtAscWindowEndAsc(
                            InferenceJobStatus.RETRY_WAIT, now, PageRequest.of(0, RETRY_BATCH_LIMIT));
            List<List<WindowTask>> retryRounds = buildRetryRounds(dueRetries, now, cancelledCleaned);
            runRoundsInParallel(retryRounds, now, modelVersion, haltedSessionsThisTick,
                    retriesAttempted, windowsEvaluated, failuresRecorded);

            // 2. Forward windows across eligible ACTIVE and unfinished ENDED sessions
            Duration windowDuration = schedulerProperties.getWindowDuration();
            Duration stride = schedulerProperties.getWindowStride();
            List<MonitoringSession> candidates = sessionRepository.findCandidateSessionsForScheduling(
                    modelVersion, windowDuration.getSeconds(), stride.getSeconds());

            if (!candidates.isEmpty()) {
                List<MonitoringSession> rotated = rotateSessions(candidates);
                List<List<WindowTask>> forwardRounds = buildForwardRounds(rotated, now, modelVersion);
                runRoundsInParallel(forwardRounds, now, modelVersion, haltedSessionsThisTick,
                        retriesAttempted, windowsEvaluated, failuresRecorded);
            }

            return new TickSummary(
                    false,
                    candidates.size(),
                    retriesAttempted.get(),
                    windowsEvaluated.get(),
                    failuresRecorded.get(),
                    cancelledCleaned.get()
            );
        } finally {
            tickRunning.set(false);
        }
    }

    private List<MonitoringSession> rotateSessions(List<MonitoringSession> sessions) {
        int size = sessions.size();
        int offset = Math.floorMod(roundRobinCursor.getAndIncrement(), size);
        if (offset == 0) {
            return sessions;
        }
        List<MonitoringSession> rotated = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            rotated.add(sessions.get((offset + i) % size));
        }
        return rotated;
    }

    private List<List<WindowTask>> buildRetryRounds(List<InferenceWindowJob> dueRetries, Instant now,
                                                    AtomicInteger cancelledCleaned) {
        if (dueRetries.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<WindowTask>> bySession = new LinkedHashMap<>();
        Map<UUID, MonitoringSession> sessionCache = new LinkedHashMap<>();
        for (InferenceWindowJob job : dueRetries) {
            MonitoringSession session = sessionCache.computeIfAbsent(job.getSessionId(),
                    id -> sessionRepository.findById(id).orElse(null));
            if (session == null || session.getStatus() == SessionStatus.CANCELLED) {
                cancelledCleaned.addAndGet(inferenceJobService.cancelOpenRetries(job.getSessionId(), now));
                continue;
            }
            bySession.computeIfAbsent(job.getSessionId(), id -> new ArrayList<>())
                    .add(new WindowTask(job.getSessionId(), job.getWindowStart(), job.getWindowEnd(), true));
        }
        return transposeToRounds(new ArrayList<>(bySession.values()));
    }

    private List<List<WindowTask>> buildForwardRounds(List<MonitoringSession> sessions, Instant now, String modelVersion) {
        Duration windowDuration = schedulerProperties.getWindowDuration();
        Duration stride = schedulerProperties.getWindowStride();
        Duration graceLag = schedulerProperties.getGraceLag();
        int cap = schedulerProperties.getMaxWindowsPerSession();

        List<List<WindowTask>> perSessionWindows = new ArrayList<>();
        for (MonitoringSession session : sessions) {
            Instant effectiveCutoff = computeEffectiveCutoff(session, now, graceLag);
            Instant maxPred = predictionRepository.findMaxWindowEnd(session.getId(), modelVersion).orElse(null);
            Instant maxJob = jobRepository.findMaxWindowEnd(session.getId(), modelVersion).orElse(null);
            Instant maxOutcome = maxOf(maxPred, maxJob);

            Instant nextWindowEnd = maxOutcome != null
                    ? maxOutcome.plus(stride)
                    : session.getStartedAt().plus(windowDuration);

            List<WindowTask> sessionTasks = new ArrayList<>(cap);
            while (sessionTasks.size() < cap && !nextWindowEnd.isAfter(effectiveCutoff)) {
                Instant windowStart = nextWindowEnd.minus(windowDuration);
                if (!windowStart.isBefore(session.getStartedAt())) {
                    sessionTasks.add(new WindowTask(session.getId(), windowStart, nextWindowEnd, false));
                }
                nextWindowEnd = nextWindowEnd.plus(stride);
            }
            if (!sessionTasks.isEmpty()) {
                perSessionWindows.add(sessionTasks);
            }
        }
        return transposeToRounds(perSessionWindows);
    }

    private static List<List<WindowTask>> transposeToRounds(List<List<WindowTask>> perSessionQueues) {
        int maxRounds = 0;
        for (List<WindowTask> q : perSessionQueues) {
            if (q.size() > maxRounds) {
                maxRounds = q.size();
            }
        }
        List<List<WindowTask>> rounds = new ArrayList<>(maxRounds);
        for (int roundIdx = 0; roundIdx < maxRounds; roundIdx++) {
            List<WindowTask> round = new ArrayList<>();
            for (List<WindowTask> q : perSessionQueues) {
                if (roundIdx < q.size()) {
                    round.add(q.get(roundIdx));
                }
            }
            rounds.add(round);
        }
        return rounds;
    }

    /**
     * Dispatches each round across the bounded worker pool and waits for the round barrier before
     * launching the next chronological window for any session. Preserves per-session strict order
     * while achieving true multi-session concurrency and round-robin fairness.
     */
    private void runRoundsInParallel(List<List<WindowTask>> rounds, Instant now, String modelVersion,
                                     Set<UUID> haltedSessionsThisTick,
                                     AtomicInteger retriesAttempted, AtomicInteger windowsEvaluated,
                                     AtomicInteger failuresRecorded) {
        for (List<WindowTask> round : rounds) {
            List<Future<?>> futures = new ArrayList<>(round.size());
            for (WindowTask task : round) {
                futures.add(executor.submit(() ->
                        executeTaskWithSessionLock(task, now, modelVersion, haltedSessionsThisTick,
                                retriesAttempted, windowsEvaluated, failuresRecorded)));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (ExecutionException ex) {
                    log.error("Unexpected failure in scheduled window task: {}", ex.getCause().getMessage(), ex.getCause());
                }
            }
        }
    }

    private void executeTaskWithSessionLock(WindowTask task, Instant now, String modelVersion,
                                            Set<UUID> haltedSessionsThisTick,
                                            AtomicInteger retriesAttempted, AtomicInteger windowsEvaluated,
                                            AtomicInteger failuresRecorded) {
        ReentrantLock lock = sessionLocks.computeIfAbsent(task.sessionId(), id -> new ReentrantLock());
        lock.lock();
        try {
            if (!task.retry() && haltedSessionsThisTick.contains(task.sessionId())) {
                return;
            }
            MonitoringSession session = sessionRepository.findById(task.sessionId()).orElse(null);
            if (session == null || session.getStatus() == SessionStatus.CANCELLED) {
                inferenceJobService.cancelOpenRetries(task.sessionId(), now);
                return;
            }
            if (session.getStatus() == SessionStatus.ENDED
                    && (session.getEndedAt() == null || task.windowEnd().isAfter(session.getEndedAt()))) {
                return;
            }

            if (predictionRepository.findBySessionIdAndModelVersionAndWindowStartAndWindowEnd(
                    task.sessionId(), modelVersion, task.windowStart(), task.windowEnd()).isPresent()) {
                if (task.retry()) {
                    jobRepository.findBySessionIdAndModelVersionAndWindowStartAndWindowEnd(
                            task.sessionId(), modelVersion, task.windowStart(), task.windowEnd()
                    ).ifPresent(job -> {
                        if (job.getStatus() == InferenceJobStatus.RETRY_WAIT) {
                            Instant safeNow = now.isBefore(job.getCreatedAt()) ? job.getCreatedAt() : now;
                            job.markSucceeded(job.getAttemptCount(), safeNow);
                            jobRepository.save(job);
                        }
                    });
                }
                return;
            }

            if (!task.retry()) {
                if (jobRepository.findBySessionIdAndModelVersionAndWindowStartAndWindowEnd(
                        task.sessionId(), modelVersion, task.windowStart(), task.windowEnd()).isPresent()) {
                    return;
                }
                Instant maxPred = predictionRepository.findMaxWindowEnd(task.sessionId(), modelVersion).orElse(null);
                Instant maxJob = jobRepository.findMaxWindowEnd(task.sessionId(), modelVersion).orElse(null);
                Instant maxOutcome = maxOf(maxPred, maxJob);
                Instant expectedNextEnd = maxOutcome != null
                        ? maxOutcome.plus(schedulerProperties.getWindowStride())
                        : session.getStartedAt().plus(schedulerProperties.getWindowDuration());
                if (!task.windowEnd().equals(expectedNextEnd)) {
                    if (task.windowEnd().isAfter(expectedNextEnd)) {
                        haltedSessionsThisTick.add(task.sessionId());
                    }
                    return;
                }
            }
            if (task.retry()) {
                InferenceWindowJob job = jobRepository.findBySessionIdAndModelVersionAndWindowStartAndWindowEnd(
                        task.sessionId(), modelVersion, task.windowStart(), task.windowEnd()).orElse(null);
                if (job == null || job.getStatus() != InferenceJobStatus.RETRY_WAIT
                        || (job.getNextRetryAt() != null && now.isBefore(job.getNextRetryAt()))) {
                    return;
                }
                retriesAttempted.incrementAndGet();
            }

            // Execute sidecar call strictly outside DB locks/transactions
            WindowEvaluationService.SidecarCallOutcome outcome = windowEvaluationService.evaluateOnce(
                    task.sessionId(), task.windowStart(), task.windowEnd(), now);
            if (outcome == null) {
                throw new IllegalStateException("WindowEvaluationService.evaluateOnce returned null outcome");
            }

            if (outcome.succeeded()) {
                inferenceJobService.recordSuccess(outcome.predictionCommand(), now);
                windowsEvaluated.incrementAndGet();
            } else if (outcome.retryable()) {
                inferenceJobService.recordRetryableFailure(
                        task.sessionId(),
                        outcome.modelVersion(),
                        task.windowStart(),
                        task.windowEnd(),
                        outcome.errorMessage(),
                        outcome.processingTimeMs(),
                        now
                );
                failuresRecorded.incrementAndGet();
            } else {
                inferenceJobService.recordNonRetryableFailure(
                        task.sessionId(),
                        outcome.modelVersion(),
                        task.windowStart(),
                        task.windowEnd(),
                        outcome.errorMessage(),
                        outcome.processingTimeMs(),
                        now
                );
                failuresRecorded.incrementAndGet();
            }
        } catch (Throwable ex) {
            if (!task.retry()) {
                haltedSessionsThisTick.add(task.sessionId());
            }
            failuresRecorded.incrementAndGet();
            log.error("Unhandled failure processing window [{}, {}) for session {}: {}",
                    task.windowStart(), task.windowEnd(), task.sessionId(), ex.getMessage(), ex);
            if (ex instanceof Error err) {
                throw err;
            }
        } finally {
            lock.unlock();
        }
    }

    private static Instant computeEffectiveCutoff(MonitoringSession session, Instant now, Duration graceLag) {
        Instant graceCutoff = now.minus(graceLag);
        if (session.getStatus() == SessionStatus.ACTIVE) {
            return graceCutoff;
        }
        if (session.getStatus() == SessionStatus.ENDED && session.getEndedAt() != null) {
            return graceCutoff.isBefore(session.getEndedAt()) ? graceCutoff : session.getEndedAt();
        }
        return Instant.EPOCH;
    }

    private static Instant maxOf(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isAfter(b) ? a : b;
    }
}
