package com.syncspace.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * Starts the realtime Redis listener once the application is ready.
 *
 * <p>The container is not auto-started (see {@link RedisRealtimeConfig}):
 * with Redis down at boot, its initial connect fails synchronously and a
 * failed bean start would take the whole application down — but the
 * contract is that the app runs fine without Redis, with local broadcast
 * only. So this bootstrap tries to start the container once the app is
 * ready (the common case: Redis is up, start succeeds immediately and
 * fan-in is live from the first request) and, only if that fails, retries
 * on a daemon thread every 5 seconds until Redis answers. Once the
 * container is running, its own recovery handles later Redis outages.
 */
@Component
public class RealtimeListenerBootstrap {

    private static final Logger log = LoggerFactory.getLogger(RealtimeListenerBootstrap.class);
    private static final long RETRY_DELAY_MILLIS = 5_000L;

    private final RedisMessageListenerContainer container;

    public RealtimeListenerBootstrap(RedisMessageListenerContainer container) {
        this.container = container;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startListenerWhenReady() {
        try {
            container.start();
            return;
        } catch (Exception ex) {
            log.warn("Realtime Redis listener not started ({}); will retry in the background",
                    ex.getMessage());
        }
        Thread retry = new Thread(() -> {
            while (!container.isRunning()) {
                try {
                    Thread.sleep(RETRY_DELAY_MILLIS);
                    container.start();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception ex) {
                    log.warn("Realtime Redis listener still not started ({}); retrying",
                            ex.getMessage());
                }
            }
        }, "redis-listener-starter");
        retry.setDaemon(true);
        retry.start();
    }
}
