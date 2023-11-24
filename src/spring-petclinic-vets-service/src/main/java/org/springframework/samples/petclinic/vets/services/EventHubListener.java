package org.springframework.samples.petclinic.vets.services;


import com.azure.spring.messaging.eventhubs.support.EventHubsHeaders;
import com.azure.spring.messaging.checkpoint.Checkpointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.samples.petclinic.vets.VetsServiceApplication;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

import static com.azure.spring.messaging.AzureHeaders.CHECKPOINTER;

@Configuration
public class EventHubListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(VetsServiceApplication.class);

    private int i = 0;

    @Bean
    public Consumer<Message<String>> consume() {
        return message -> {
            Checkpointer checkpointer = (Checkpointer) message.getHeaders().get(CHECKPOINTER);
            LOGGER.info("New message received: '{}', partition key: {}, sequence number: {}, offset: {}, enqueued time: {}",
                    message.getPayload(),
                    message.getHeaders().get(EventHubsHeaders.PARTITION_KEY),
                    message.getHeaders().get(EventHubsHeaders.SEQUENCE_NUMBER),
                    message.getHeaders().get(EventHubsHeaders.OFFSET),
                    message.getHeaders().get(EventHubsHeaders.ENQUEUED_TIME)
            );

            checkpointer.success()
                    .doOnSuccess(success -> LOGGER.info("Message '{}' successfully checkpointed", message.getPayload()))
                    .doOnError(error -> LOGGER.error("Exception found", error))
                    .block();
        };
    }
}