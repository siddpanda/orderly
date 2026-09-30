package com.example.orderly.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the topics this service needs. Spring Kafka creates them on the
 * broker at startup if they do not exist yet. Producer/consumer (de)serializers
 * are configured in {@code application.properties}; Boot's auto-configuration
 * picks them up.
 */
@Configuration
public class KafkaConfig {

    @Value("${orderly.kafka.topic:orders}")
    private String ordersTopic;

    @Bean
    public NewTopic ordersTopic() {
        return TopicBuilder.name(ordersTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
