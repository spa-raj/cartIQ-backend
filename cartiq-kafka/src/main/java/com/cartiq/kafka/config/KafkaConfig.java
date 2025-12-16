package com.cartiq.kafka.config;

import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializerConfig;
import io.confluent.kafka.streams.serdes.avro.ReflectionAvroDeserializer;
import io.confluent.kafka.streams.serdes.avro.ReflectionAvroSerializer;
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

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka configuration for Confluent Cloud with Schema Registry.
 * Uses Confluent's ReflectionAvroSerializer for POJO to Avro serialization.
 *
 * Required environment variables:
 * - CONFLUENT_BOOTSTRAP_SERVERS: Your Confluent Cloud bootstrap server
 * - CONFLUENT_API_KEY: Your Confluent Cloud API key
 * - CONFLUENT_API_SECRET: Your Confluent Cloud API secret
 * - CONFLUENT_SCHEMA_REGISTRY_URL: Your Confluent Schema Registry URL
 * - CONFLUENT_SR_API_KEY: Schema Registry API key
 * - CONFLUENT_SR_API_SECRET: Schema Registry API secret
 */
@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${confluent.api.key:}")
    private String apiKey;

    @Value("${confluent.api.secret:}")
    private String apiSecret;

    @Value("${confluent.schema.registry.url:}")
    private String schemaRegistryUrl;

    @Value("${confluent.schema.registry.api.key:}")
    private String schemaRegistryApiKey;

    @Value("${confluent.schema.registry.api.secret:}")
    private String schemaRegistryApiSecret;

    @Value("${spring.kafka.consumer.group-id:cartiq-group}")
    private String groupId;

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

    private Map<String, Object> schemaRegistryConfigs() {
        Map<String, Object> props = new HashMap<>();
        if (schemaRegistryUrl != null && !schemaRegistryUrl.isEmpty()) {
            props.put(KafkaAvroSerializerConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
            // Schema Registry authentication for Confluent Cloud
            if (schemaRegistryApiKey != null && !schemaRegistryApiKey.isEmpty()) {
                props.put("basic.auth.credentials.source", "USER_INFO");
                props.put("schema.registry.basic.auth.user.info",
                        schemaRegistryApiKey + ":" + schemaRegistryApiSecret);
            }
            // Auto-register schemas
            props.put(KafkaAvroSerializerConfig.AUTO_REGISTER_SCHEMAS, true);
            // Use TopicNameStrategy for subject naming
            props.put(KafkaAvroSerializerConfig.VALUE_SUBJECT_NAME_STRATEGY,
                    "io.confluent.kafka.serializers.subject.TopicNameStrategy");
        }
        return props;
    }

    // ==================== PRODUCER CONFIG ====================

    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        Map<String, Object> props = new HashMap<>(commonConfigs());
        props.putAll(schemaRegistryConfigs());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        // Use ReflectionAvroSerializer for POJO serialization
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ReflectionAvroSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    // ==================== CONSUMER CONFIG ====================

    @Bean
    public ConsumerFactory<String, Object> consumerFactory() {
        Map<String, Object> props = new HashMap<>(commonConfigs());
        props.putAll(schemaRegistryConfigs());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // Use ReflectionAvroDeserializer for POJO deserialization
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ReflectionAvroDeserializer.class);
        props.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, false);

        return new DefaultKafkaConsumerFactory<>(props);
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
