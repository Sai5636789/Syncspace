package com.syncspace.realtime;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Subscribes this instance to the realtime Redis channel so events
 * published by other backend instances are broadcast to this instance's
 * STOMP sessions. If Redis is unreachable at startup the container logs
 * the failure and retries in the background; the application still starts
 * and local broadcast keeps working.
 */
@Configuration
public class RedisRealtimeConfig {

    @Bean
    public RedisMessageListenerContainer realtimeListenerContainer(
            RedisConnectionFactory connectionFactory,
            RealtimeEventPublisher publisher) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(
                (message, pattern) -> publisher.handleRedisMessage(new String(message.getBody())),
                new ChannelTopic(RealtimeEventPublisher.REDIS_CHANNEL));
        return container;
    }
}
