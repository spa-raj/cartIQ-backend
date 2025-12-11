package com.cartiq.kafka.config;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka configuration for Confluent Cloud.
 * Uses Jackson 3 serializers (JacksonJsonSerializer/JacksonJsonDeserializer).
 *
 * Required environment variables:
 * - CONFLUENT_BOOTSTRAP_SERVERS: Your Confluent Cloud bootstrap server
 * - CONFLUENT_API_KEY: Your Confluent Cloud API key
 * - CONFLUENT_API_SECRET: Your Confluent Cloud API secret
 */
@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${confluent.api.key:}")
    private String apiKey;

    @Value("${confluent.api.secret:}")
    private String apiSecret;

    @Value("${spring.kafka.consumer.group-id:cartiq-group}")
    private String groupId;

    @Bean
    public JsonMapper kafkaJsonMapper() {
        return JsonMapper.builder()
                .findAndAddModules()
                .build();
    }

    private Map<String, Object> commonConfigs() {
        Map<String, Object> props = new HashMap<>();
        props.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

        // Confluent Cloud authentication (if credentials provided)
        if (apiKey != null && !apiKey.isEmpty()) {
            props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_SSL");
            props.put(SaslConfigs.SASL_MECHANISM, "PLAIN");
            props.put(SaslConfigs.SASL_JAAS_CONFIG,
                    String.format("org.apache.kafka.common.security.plain.PlainLoginModule required username='%s' password='%s';",
                            apiKey, apiSecret));
        }

        return props;
    }

    // ==================== PRODUCER CONFIG ====================

    @Bean
    public ProducerFactory<String, Object> producerFactory(JsonMapper kafkaJsonMapper) {
        Map<String, Object> props = new HashMap<>(commonConfigs());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

        JacksonJsonSerializer<Object> jsonSerializer = new JacksonJsonSerializer<>(kafkaJsonMapper);

        return new DefaultKafkaProducerFactory<>(props, new StringSerializer(), jsonSerializer);
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    // ==================== CONSUMER CONFIG ====================

    @Bean
    public ConsumerFactory<String, Object> consumerFactory(JsonMapper kafkaJsonMapper) {
        Map<String, Object> props = new HashMap<>(commonConfigs());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        JacksonJsonDeserializer<Object> jsonDeserializer = new JacksonJsonDeserializer<>(Object.class, kafkaJsonMapper);
        jsonDeserializer.addTrustedPackages("com.cartiq.*");

        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), jsonDeserializer);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(3);
        return factory;
    }
}
