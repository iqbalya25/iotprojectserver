package org.example.iotproject.Mqtt.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class MqttService {

    private final MqttClient mqttClient;
    private final ObjectMapper objectMapper;

    public MqttService(MqttClient mqttClient) {
        this.mqttClient = mqttClient;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())  // Add this
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);  // Optional: formats dates as ISO strings
        log.info("MqttService initialized with client ID: {}", mqttClient.getClientId());
    }

    public void publish(String topic, Object payload) {
        try {
            String jsonPayload = objectMapper.writeValueAsString(payload);
            MqttMessage message = new MqttMessage(jsonPayload.getBytes());
            mqttClient.publish(topic, message);
            log.info("Published message to topic: {}", topic);
        } catch (Exception e) {
            log.error("Failed to publish MQTT message - Topic: {}, Error: {}", topic, e.getMessage());
        }
    }

    public void subscribe(String topic, IMqttMessageListener messageListener) {
        try {
            mqttClient.subscribe(topic, messageListener);
            log.info("Subscribed to topic: {}", topic);
        } catch (MqttException e) {
            log.error("Failed to subscribe to topic: {}", topic, e);
        }
    }
}