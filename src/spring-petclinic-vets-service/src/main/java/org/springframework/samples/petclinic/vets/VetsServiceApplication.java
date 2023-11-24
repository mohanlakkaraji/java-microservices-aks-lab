package org.springframework.samples.petclinic.vets;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.samples.petclinic.vets.system.VetsProperties;

import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.messaging.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Maciej Szarlinski
 */
@EnableDiscoveryClient
@SpringBootApplication
@EnableConfigurationProperties(VetsProperties.class)
public class VetsServiceApplication {

   private static final Logger LOGGER = LoggerFactory.getLogger(VetsServiceApplication.class);

   public static void main(String[] args) {
      SpringApplication.run(VetsServiceApplication.class, args);
   }

   @ServiceActivator(inputChannel = "telemetry.$Default.errors")
    public void consumerError(Message<?> message) {
        LOGGER.error("Handling consumer ERROR: " + message);
    }
}