package me.fertiz.queryscope.agent.profiler;

import jdk.jfr.FlightRecorder;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingStream;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

final class JfrSampler {

    static final String SOURCE = "JDK Flight Recorder";

    private static final String EXECUTION_SAMPLE = "jdk.ExecutionSample";
    private static final String NATIVE_SAMPLE = "jdk.NativeMethodSample";

    private JfrSampler() {
    }

    static Runnable start(SampleStore store, Duration interval) {
        if (!FlightRecorder.isAvailable()) {
            throw new IllegalStateException("JDK Flight Recorder is not available in this JVM");
        }
        RecordingStream stream = new RecordingStream();
        stream.enable(EXECUTION_SAMPLE).withPeriod(interval);
        stream.enable(NATIVE_SAMPLE).withPeriod(interval);
        stream.onEvent(EXECUTION_SAMPLE, event -> record(store, event, true));
        stream.onEvent(NATIVE_SAMPLE, event -> record(store, event, false));
        Thread consumer = new Thread(stream::start, Sampling.OWN_THREADS + "flight-recorder");
        consumer.setDaemon(true);
        consumer.start();
        return () -> stop(stream);
    }

    private static void stop(RecordingStream stream) {
        try {
            RecordingStream.class.getMethod("stop").invoke(stream);
        } catch (ReflectiveOperationException e) {
            stream.close();
        }
    }

    private static void record(SampleStore store, RecordedEvent event, boolean onCpu) {
        RecordedStackTrace stack = event.getStackTrace();
        RecordedThread thread = event.getThread("sampledThread");
        if (stack == null || thread == null || thread.getJavaThreadId() <= 0) {
            return;
        }
        String name = thread.getJavaName();
        if (Sampling.isOwnThread(name)) {
            return;
        }
        List<RecordedFrame> frames = stack.getFrames();
        List<String> methods = new ArrayList<>(frames.size());
        for (int index = frames.size() - 1; index >= 0; index--) {
            RecordedFrame frame = frames.get(index);
            if (frame.isJavaFrame()) {
                RecordedMethod method = frame.getMethod();
                methods.add(Sampling.methodName(method.getType().getName(), method.getName()));
            }
        }
        store.add(event.getStartTime().toEpochMilli(), thread.getJavaThreadId(), name, onCpu, stack.isTruncated(),
                methods);
    }
}
