package com.example.orderly.messaging;

import com.example.orderly.dto.OrderCreatedEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes order domain events to Kafka. The topic name is externalized so it
 * can differ per environment.
 */
@Component
public class OrderEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String topic;

    public OrderEventPublisher(KafkaTemplate<String, Object> kafkaTemplate,
                               @Value("${orderly.kafka.topic:orders}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publish(OrderCreatedEvent event) {
        kafkaTemplate.send(topic, String.valueOf(event.orderId()), event);
    }
}
