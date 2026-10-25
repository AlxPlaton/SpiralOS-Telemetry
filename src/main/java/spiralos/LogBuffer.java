package spiralos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/** Keeps the last 2000 application log events in memory for the Logs page. */
@Component
public class LogBuffer extends AppenderBase<ILoggingEvent> {

    public record Entry(Long time, String level, String logger, String message) {}

    private static final int CAPACITY = 2000;
    private final ArrayDeque<Entry> buffer = new ArrayDeque<>();

    @PostConstruct
    void attach() {
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext ctx) {
            setContext(ctx);
            setName("spiralos-log-buffer");
            start();
            ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).addAppender(this);
        }
    }

    @PreDestroy
    void detach() {
        stop();
    }

    @Override
    protected void append(ILoggingEvent event) {
        String logger = event.getLoggerName();
        int dot = logger.lastIndexOf('.');
        Entry entry = new Entry(event.getTimeStamp(), event.getLevel().toString(),
                dot >= 0 ? logger.substring(dot + 1) : logger, event.getFormattedMessage());
        synchronized (buffer) {
            if (buffer.size() >= CAPACITY) {
                buffer.removeFirst();
            }
            buffer.addLast(entry);
        }
    }

    public List<Entry> snapshot() {
        synchronized (buffer) {
            return new ArrayList<>(buffer);
        }
    }
}
