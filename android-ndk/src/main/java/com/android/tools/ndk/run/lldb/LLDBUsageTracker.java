//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package com.android.tools.ndk.run.lldb;

import com.android.ddmlib.IDevice;
import com.android.tools.analytics.Percentiles;
import com.android.tools.analytics.UsageTracker;
import com.android.tools.idea.diagnostics.crash.StudioExceptionReport;
import com.android.tools.idea.stats.AndroidStudioUsageTracker;
import com.android.tools.ndk.run.AndroidNativeDeviceException;
import com.android.tools.ndk.run.lldb.ConnectLLDBTask.SessionStarterType;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.wireless.android.sdk.stats.AndroidStudioEvent;
import com.google.wireless.android.sdk.stats.LLDBFrontendDetails;
import com.google.wireless.android.sdk.stats.LldbSessionEndDetails;
import com.google.wireless.android.sdk.stats.LldbSessionStartDetails;
import com.google.wireless.android.sdk.stats.AndroidStudioEvent.Builder;
import com.google.wireless.android.sdk.stats.AndroidStudioEvent.DebuggerType;
import com.google.wireless.android.sdk.stats.AndroidStudioEvent.EventCategory;
import com.google.wireless.android.sdk.stats.AndroidStudioEvent.EventKind;
import com.google.wireless.android.sdk.stats.LldbPercentileEstimator.Metric;
import com.google.wireless.android.sdk.stats.LldbSessionStartDetails.StarterType;
import com.intellij.concurrency.JobScheduler;
import com.intellij.ide.AppLifecycleListener;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.jetbrains.cidr.execution.debugger.backend.lldb.LLDBDriverException;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class LLDBUsageTracker {
    private static final Object TIMINGS_LOCK = new Object();
    private static final double[] ESTIMATION_TARGETS = new double[]{0.5D, 0.9D, 0.99D};
    private static final int NUM_RAW_SAMPLES = 80;
    private static final int INTERVAL_MINUTES = 60;
    private static final int INITIAL_DELAY_MINUTES = 60;
    private static HashMap<Metric, Percentiles> ourEstimators = new HashMap<>();
    private static final ImmutableSet<Class<? extends Throwable>> THROWABLE_CLASSES_TO_TRACK_MESSAGES;

    public LLDBUsageTracker() {
    }

    @NotNull
    private static String getExceptionDescription(@Nullable Throwable e) {
        if (e == null) {
            return "";
        } else {
            Throwable cause = StudioExceptionReport.getRootCause(e);
            return THROWABLE_CLASSES_TO_TRACK_MESSAGES.contains(cause.getClass()) ? Strings.nullToEmpty(cause.getMessage()) : StudioExceptionReport.getDescription(e);
        }
    }

    private static void log(Builder studioEventBuilder) {
        UsageTracker.log(studioEventBuilder);
    }

    private static Builder newBuilder(EventKind eventKind) {
        return AndroidStudioEvent.newBuilder().setCategory(EventCategory.LLDB).setKind(eventKind);
    }

    @NotNull
    private static StarterType convertStarterType(SessionStarterType type) {
        switch(type) {
            case INJECTOR:
            case INJECTOR_YAMA:
                return StarterType.INJECTOR_STARTER_TYPE;
            case ROOT_SHELL:
                return StarterType.ROOT_SHELL_STARTER_TYPE;
            case RUN_AS_SHELL:
                return StarterType.RUN_AS_SHELL_STARTER_TYPE;
            default:
                throw new IllegalArgumentException("Could not convert unknown starter type : " + type);
        }
    }

    public static void sessionStarted(@NotNull IDevice device, DebuggerType debuggerType, String sessionId, boolean chosenByAuto, SessionStarterType starterType) {
        log(newBuilder(EventKind.LLDB_SESSION_STARTED).setLldbSessionStartDetails(LldbSessionStartDetails.newBuilder().setAutoDebugger(chosenByAuto).setStarterType(convertStarterType(starterType)).setDebugSessionId(sessionId).setDebuggerType(debuggerType).setDeviceInfo(AndroidStudioUsageTracker.deviceToDeviceInfo(device)).setLldbVersion(LLDBSdkPkgInstaller.PINNED_REVISION.toString())));
    }

    public static void sessionStopped(@NotNull String sessionId, long stops, long errors) {
        log(newBuilder(EventKind.LLDB_SESSION_ENDED).setLldbSessionEndDetails(LldbSessionEndDetails.newBuilder().setDebugSessionId(sessionId).setStops(stops).setErrors(errors)));
    }

    public static void sessionFailed(@NotNull Throwable e, @NotNull String sessionId, long stops, long errors) {
        sessionFailed(getExceptionDescription(e), sessionId, stops, errors);
    }

    public static void sessionFailed(@NotNull String error, @NotNull String sessionId, long stops, long errors) {
        log(newBuilder(EventKind.LLDB_SESSION_ENDED).setLldbSessionEndDetails(LldbSessionEndDetails.newBuilder().setDebugSessionId(sessionId).setStops(stops).setErrors(errors).setFailureMessage(error)));
    }

    public static void installStarted() {
        log(newBuilder(EventKind.LLDB_INSTALL_STARTED));
    }

    public static void installFailed(@Nullable Throwable e) {
        log(newBuilder(EventKind.LLDB_INSTALL_FAILED).setLldbSessionFailureMessage(getExceptionDescription(e)));
    }

    public static void sessionUsedWatchpoints() {
        log(newBuilder(EventKind.LLDB_SESSION_USED_WATCHPOINTS));
    }

    public static void frontendExited(int exitCode) {
        log(newBuilder(EventKind.LLDB_FRONTEND_EXITED).setLldbFrontendDetails(LLDBFrontendDetails.newBuilder().setExitCode(exitCode)));
    }

    private static void logAndResetPerformanceStats() {
        HashMap<Metric, Percentiles> newEstimators = new HashMap<>();
        HashMap<Metric, Percentiles> estimators;
        synchronized(TIMINGS_LOCK) {
            estimators = ourEstimators;
            ourEstimators = newEstimators;
        }

        if (estimators != null) {
            logPerformanceStats(estimators);
        }

    }

    public static void storeLldbActionTime(Metric m, double sample) {
        synchronized(TIMINGS_LOCK) {
            Percentiles p = (Percentiles)ourEstimators.get(m);
            if (p == null) {
                p = new Percentiles(ESTIMATION_TARGETS, 80);
                ourEstimators.put(m, p);
            }

            p.addSample(sample);
        }
    }

    private static void logPerformanceStats(@NotNull Map<Metric, Percentiles> estimators) {
        if (!estimators.isEmpty()) {
            Builder builder = AndroidStudioEvent.newBuilder();
            builder.setCategory(EventCategory.LLDB).setKind(EventKind.LLDB_PERFORMANCE_STATS);
            com.google.wireless.android.sdk.stats.LldbPerformanceStats.Builder stats = builder.getLldbPerformanceStatsBuilder();

            for (Entry<Metric, Percentiles> metricPercentilesEntry : estimators.entrySet()) {
                stats.addEstimatorBuilder().setMetric(metricPercentilesEntry.getKey()).setEstimator((metricPercentilesEntry.getValue()).export());
            }

            log(builder);
        }
    }

    static {
        Application application = ApplicationManager.getApplication();
        application.getMessageBus().connect(application).subscribe(AppLifecycleListener.TOPIC, new AppLifecycleListener() {
            public void appClosing() {
                LLDBUsageTracker.logAndResetPerformanceStats();
            }
        });
        JobScheduler.getScheduler().scheduleWithFixedDelay(LLDBUsageTracker::logAndResetPerformanceStats, 60L, 60L, TimeUnit.MINUTES);
        THROWABLE_CLASSES_TO_TRACK_MESSAGES = ImmutableSet.of(AndroidNativeDeviceException.class, LLDBDriverException.class);
    }
}
