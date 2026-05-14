package com.project.inventory.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

@Configuration
public class KafkaConfig {

    @Value("${app.kafka.topics.inventory-updates}")
    private String inventoryUpdatesTopic;

    @Value("${app.kafka.topics.inventory-updates-dlq}")
    private String dlqTopic;

    @Value("${app.kafka.topics.low-stock-alerts}")
    private String lowStockAlertsTopic;

    @Value("${app.kafka.topics.restock-recommendations}")
    private String restockRecommendationsTopic;

    @Bean
    public NewTopic inventoryUpdatesTopic() {
        return TopicBuilder.name(inventoryUpdatesTopic).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic inventoryUpdatesDlqTopic() {
        return TopicBuilder.name(dlqTopic).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic lowStockAlertsTopic() {
        return TopicBuilder.name(lowStockAlertsTopic).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic restockRecommendationsTopic() {
        return TopicBuilder.name(restockRecommendationsTopic).partitions(1).replicas(1).build();
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }
}
