package org.jahia.community.modules.customgpt.testutil;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Test helper that attaches a Logback {@link ListAppender} to a class's SLF4J logger so tests can
 * assert on emitted log lines (audit trails, security warnings, error logging).
 *
 * <p>Requires the {@code logback-classic} test dependency to be on the classpath so that
 * {@code LoggerFactory.getLogger(...)} actually returns a {@code ch.qos.logback.classic.Logger}
 * instance (rather than a no-op logger) at test time.
 */
public final class LogCapture {

    private LogCapture() {
    }

    /**
     * Attaches and starts a fresh {@link ListAppender} on {@code loggerClass}'s logger.
     * Call {@link #detach(Class, ListAppender)} in a {@code finally} block (or an {@code @After} method)
     * to avoid leaking appenders across tests.
     */
    public static ListAppender<ILoggingEvent> attach(Class<?> loggerClass) {
        final Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(loggerClass);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    public static void detach(Class<?> loggerClass, ListAppender<ILoggingEvent> appender) {
        final Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(loggerClass);
        logger.detachAppender(appender);
        appender.stop();
    }
}
