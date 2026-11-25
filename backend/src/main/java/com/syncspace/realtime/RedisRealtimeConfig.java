package com.syncspace.realtime;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Subscribes this instance to the realtime Redis channel so events
 * published by other backend instances are broadcast to this instance's
 * STOMP sessions. If Redis is unreachable at startup the container is
 * started later by {@link RealtimeListenerBootstrap}, which retries in
 * the background; the application itself always starts, and local
 * broadcast keeps working.
 *
 * <p>The publish executor is the other half of the best-effort contract:
 * {@link RealtimeEventPublisher} fans events out to Redis on this single
 * background thread, never on the caller's thread. Lettuce queues commands
 * while it reconnects, so a synchronous publish during an outage would
 * stall the caller for up to the 60 s command timeout; here the caller
 * only ever pays for the local broadcast. The queue is bounded (100) with
 * a discard policy: during a long outage excess fan-out publishes are
 * dropped rather than piling up — durable state lives in PostgreSQL, and
 * fan-out resumes when Redis comes back. The listener container keeps the
 * default Lettuce behavior on purpose: rejecting commands while
 * disconnected would make its startup subscribe fail and take the whole
 * application context down with it.
 */
@Configuration
public class RedisRealtimeConfig {

    @Bean
    public Executor realtimePublishExecutor() {
        return new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(100),
                new ThreadPoolExecutor.DiscardPolicy());
    }

    @Bean
    public RedisMessageListenerContainer realtimeListenerContainer(
            RedisConnectionFactory connectionFactory,
            RealtimeEventPublisher publisher) {
        // Not auto-started: SmartLifecycle's default isAutoStartup() is true
        // and there is no setter, so override it. With Redis down, start()
        // throws (the initial connect is synchronous — Lettuce only queues
        // commands on a connection that was established and then lost) and
        // the failed bean start takes the whole application context down.
        // The bootstrap below starts the container once the app is ready
        // and retries in the background until Redis answers.
        RedisMessageListenerContainer container = new RedisMessageListenerContainer() {
            @Override
            public boolean isAutoStartup() {
                return false;
            }
        };
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(
                (message, pattern) -> publisher.handleRedisMessage(new String(message.getBody())),
                new ChannelTopic(RealtimeEventPublisher.REDIS_CHANNEL));
        return container;
    }
}
