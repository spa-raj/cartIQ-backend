package com.cartiq.kafka.producer;

import com.cartiq.kafka.config.KafkaTopics;
import com.cartiq.kafka.dto.KafkaEvents.AISearchEvent;
import com.cartiq.kafka.dto.KafkaEvents.CartEvent;
import com.cartiq.kafka.dto.KafkaEvents.OrderEvent;
import com.cartiq.kafka.dto.KafkaEvents.ProductViewEvent;
import com.cartiq.kafka.dto.KafkaEvents.UserEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * Service for publishing events to Kafka topics.
 * Inject this into your controllers/services to stream events.
 *
 * Topics (matching Confluent Cloud):
 * - user-events: User session events
 * - product-views: Product page views
 * - cart-events: Cart actions
 * - order-events: Order transactions
 * - user-profiles: Flink output (consumed, not produced here)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    // ==================== INPUT TOPICS (4) ====================

    /**
     * Publish user session events (login, logout, page visits)
     */
    public void publishUserEvent(UserEvent event) {
        send(KafkaTopics.USER_EVENTS, event.getUserId(), event);
    }

    /**
     * Publish product view events
     */
    public void publishProductView(ProductViewEvent event) {
        send(KafkaTopics.PRODUCT_VIEWS, event.getUserId(), event);
    }

    /**
     * Publish cart events (add, remove, update quantity)
     */
    public void publishCartEvent(CartEvent event) {
        send(KafkaTopics.CART_EVENTS, event.getUserId(), event);
    }

    /**
     * Publish order events (placed, completed, cancelled)
     */
    public void publishOrderEvent(OrderEvent event) {
        send(KafkaTopics.ORDER_EVENTS, event.getUserId(), event);
    }

    /**
     * Publish AI search/chat events (tool calls, RAG searches)
     */
    public void publishAISearchEvent(AISearchEvent event) {
        send(KafkaTopics.AI_EVENTS, event.getUserId(), event);
    }

    // Note: user-profiles topic is populated by Flink, not by this producer

    // ==================== GENERIC SEND ====================

    private void send(String topic, String key, Object event) {
        CompletableFuture<SendResult<String, Object>> future = kafkaTemplate.send(topic, key, event);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to send event to {}: {}", topic, ex.getMessage());
            } else {
                log.debug("Sent to {} partition {} offset {}",
                        topic,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            }
        });
    }

    /**
     * Send to custom topic (for dynamic use cases)
     */
    public void sendToTopic(String topic, String key, Object event) {
        send(topic, key, event);
    }
}
