package org.example.iotproject.Master.service.impl;

import aj.org.objectweb.asm.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghgande.j2mod.modbus.io.ModbusTCPTransaction;
import com.ghgande.j2mod.modbus.msg.*;
import com.ghgande.j2mod.modbus.net.TCPMasterConnection;
import com.ghgande.j2mod.modbus.procimg.SimpleRegister;
import jakarta.annotation.PostConstruct;
import org.example.iotproject.Address.entity.Address;
import org.example.iotproject.Address.repository.AddressRepository;
import org.example.iotproject.Device.entity.Device;
import org.example.iotproject.Device.service.DeviceService;
import org.example.iotproject.DeviceStatus.entity.DeviceStatus;
import org.example.iotproject.Master.entity.Master;
import org.example.iotproject.Master.repository.MasterRepository;
import org.example.iotproject.Master.service.MasterService;
import org.example.iotproject.Mqtt.service.MqttService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class MasterServiceImpl implements MasterService {
    private static final Logger logger = (Logger) LoggerFactory.getLogger(MasterServiceImpl.class);
    private static final int PULSE_DURATION_MS = 100;
    private String connectionStatus = "disconnected";
    private boolean manuallyDisconnected = true;

    private final AddressRepository addressRepository;
    private final MasterRepository masterRepository;
    private final DeviceService deviceService;
    private final MqttService mqttService;

    private TCPMasterConnection connection;
    private int slaveId;


    public MasterServiceImpl(AddressRepository addressRepository, MasterRepository masterRepository, DeviceService deviceService, MqttService mqttService) {
        this.addressRepository = addressRepository;
        this.masterRepository = masterRepository;
        this.deviceService = deviceService;
        this.mqttService = mqttService;
    }

    @Override
    public void connectToMaster(String masterIpAddress) throws Exception {
        manuallyDisconnected = false;
        Optional<Master> plcMaster = masterRepository.findByMasterIpAddress(masterIpAddress);
        if (plcMaster.isEmpty()) {
            throw new Exception("PLC Master configuration not found for " + masterIpAddress);
        }

        Master master = plcMaster.get();
        InetAddress addr = InetAddress.getByName(master.getMasterIpAddress());
        this.slaveId = master.getPlcId();

        this.connection = new TCPMasterConnection(addr);
        connection.setPort(master.getMasterPort());

        if (!connection.isConnected()) {
            connection.connect();
            logger.info("Connected to {}:{}", master.getMasterIpAddress(), master.getMasterPort());
            connectionStatus = "Connected";
        } else {
            connectionStatus = "Connection Failed";
        }

        publishConnectionStatus();
    }

    @Override
    public void disconnectFromMaster() {
        if (connection != null && connection.isConnected()) {
            try {
                connection.close();
                connectionStatus = "Disconnected";
                manuallyDisconnected = true;
                logger.info("Successfully disconnected from master PLC.");
                publishConnectionStatus();
            } catch (Exception e) {
                logger.error("Failed to disconnect from master PLC.", e);
            }
        } else {
            logger.warn("Attempted to disconnect, but no active connection was found.");
        }
    }

    private boolean isConnectedAndReady() {
        return connection != null
                && connection.isConnected()
                && "Connected".equals(connectionStatus)
                && !manuallyDisconnected;
    }

    private void checkConnection() throws Exception {
        if (!isConnectedAndReady()) {
            throw new Exception("PLC not connected. Current status: " + connectionStatus);
        }
    }

    private void executeCommand(String addressName, Boolean isOnCommand ) throws Exception {
        if ("Connection Failed".equals(connectionStatus) || "Disconnected".equals(connectionStatus))   {
            throw new Exception("Connection Failed");
        }

        Optional<Address> addressOpt = addressRepository.findByAddressName(addressName);
        if (addressOpt.isEmpty()) {
            logger.error("Address {} not found in database", addressName);
            throw new IOException("Address not found in database");
        }

        int modbusAddress = addressOpt.get().getModbusAddress();
        sendMomentaryPulse(modbusAddress, isOnCommand);
    }

    private Boolean getStatus(String addressName) throws Exception {
        if ("Connection Failed".equals(connectionStatus) || "Disconnected".equals(connectionStatus))   {
            throw new Exception("Connection Failed");
        }

        Optional<Address> addressOpt = addressRepository.findByAddressName(addressName);
        if (addressOpt.isEmpty()) {
            logger.error("Address {} not found in database", addressName);
            throw new IOException("Address not found in database");
        }

        int modbusAddress = addressOpt.get().getModbusAddress();
       return readCoil(modbusAddress);
    }


    @Override
    public void turnOnBlower1() throws Exception {
        checkConnection();
        executeCommand("Blower_1_On", true);
        boolean status = getStatus("Blower_1_Status");
        publishBlowerStatus(status);
    }

    @Override
    public void turnOffBlower1() throws Exception {
        checkConnection();
        executeCommand("Blower_1_Off", false);
        boolean status = getStatus("Blower_1_Status");
        publishBlowerStatus(status);
    }

    @Override
    public boolean getBlower1Status() throws Exception {
        checkConnection();
        return getStatus("Blower_1_Status");
    }

    private void sendMomentaryPulse(int coil , Boolean isOnCommand) throws IOException {
        try {
            writeCoil(coil, true, isOnCommand);
            Thread.sleep(PULSE_DURATION_MS);
            writeCoil(coil, false, isOnCommand);
            logger.info("Successfully sent momentary pulse to coil {}", coil);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while sending momentary pulse to coil {}", coil, e);
            throw new IOException("Failed to send momentary pulse", e);
        }
    }

    private void writeCoil(int coil, boolean state, Boolean isOnCommand) throws IOException {
        try {
            if (!connection.isConnected()  && !manuallyDisconnected) {
                connection.connect();
            }
            WriteCoilRequest req = new WriteCoilRequest(coil, state);
            req.setUnitID(slaveId);
            ModbusResponse response = executeTransaction(req);
            if (!(response instanceof WriteCoilResponse)) {
                throw new IOException("Unexpected response type");
            }
            logger.info("Successfully wrote to coil {}: {}", coil, state);
            if (isOnCommand && state) {
                logger.info("Blower is turning ON, saving device status...");
                DeviceStatus deviceStatus = new DeviceStatus();
                Master master = masterRepository.findByPlcId(slaveId);
                deviceStatus.setMaster(master);

                Device device = deviceService.getDeviceByName("Blower_1");
                deviceStatus.setDevice(device);
                deviceStatus.setStatus(state);

//                deviceStatusService.saveDeviceStatus(deviceStatus);
                logger.info("Device status saved for turning on blower.");
            }
        } catch (Exception e) {
            logger.error("Error writing to coil {}", coil, e);
            throw new IOException("Failed to write to coil", e);
        }
    }

    private boolean readCoil(int coil) throws IOException {
        try {
            if (!connection.isConnected()  && !manuallyDisconnected) {
                connection.connect();
            }
            ReadCoilsRequest req = new ReadCoilsRequest(coil, 1);
            req.setUnitID(slaveId);
            ModbusResponse response = executeTransaction(req);
            if (response instanceof ReadCoilsResponse) {
                ReadCoilsResponse readResponse = (ReadCoilsResponse) response;
                boolean state = readResponse.getCoilStatus(0);
                logger.info("Successfully read from coil {}: {}", coil, state);
                return state;
            }

            throw new IOException("Unexpected response type");
        } catch (Exception e) {
            logger.error("Error reading from coil {}", coil, e);
            throw new IOException("Failed to read from coil", e);
        }
    }

    @Scheduled(fixedRate = 5000)
    public void checkConnectionStatus() {
        // Only check if we're supposed to be connected
        if (!manuallyDisconnected && connection != null) {
            try {
                Optional<Address> connectionCheckAddress = addressRepository.findByAddressName("Connection_Check");
                if (connectionCheckAddress.isEmpty()) {
                    connectionStatus = "Configuration Error";
                    publishConnectionStatus();
                    return;
                }

                if (!connection.isConnected()) {
                    connectionStatus = "Disconnected";
                    publishConnectionStatus();
                    return;
                }

                // Try to read from PLC to verify connection
                int modbusAddress = connectionCheckAddress.get().getModbusAddress();
                ReadCoilsRequest req = new ReadCoilsRequest(modbusAddress, 1);
                req.setUnitID(slaveId);
                executeTransaction(req);

                connectionStatus = "Connected";
            } catch (Exception e) {
                connectionStatus = "Connection Failed";
                logger.error("Failed to read from PLC: {}", e.getMessage());
            }
            publishConnectionStatus();
        }
    }

    private void publishConnectionStatus() {
        try {
            logger.info("Preparing to publish connection status to MQTT");
            Map<String, Object> status = new HashMap<>();
            status.put("status", connectionStatus);
            status.put("timestamp", Instant.now());
            status.put("masterId", this.slaveId);
            status.put("ipAddress", connection != null ? connection.getAddress().getHostAddress() : "Not Connected");

            logger.info("Publishing connection status: {}", status);
            mqttService.publish("plc/connection/status", status);
        } catch (Exception e) {
            logger.error("Failed to publish connection status", e);
        }
    }

    private void publishBlowerStatus(boolean blowerStatus) {
        try {
            logger.info("Preparing to publish blower status to MQTT");
            Map<String, Object> status = new HashMap<>();
            status.put("status", blowerStatus ? "ON" : "OFF");
            status.put("timestamp", Instant.now());
            status.put("deviceId", "Blower_1");
            status.put("masterId", this.slaveId);

            logger.info("Publishing blower status: {}", status);
            mqttService.publish("plc/blower/status", status);
        } catch (Exception e) {
            logger.error("Failed to publish blower status", e);
        }
    }

    private int readHoldingRegister(int address) throws IOException {
        try {
            if (!connection.isConnected()  && !manuallyDisconnected) {
                connection.connect();
            }
            ReadMultipleRegistersRequest req = new ReadMultipleRegistersRequest(address, 1);
            req.setUnitID(slaveId);
            ModbusResponse response = executeTransaction(req);

            if (response instanceof ReadMultipleRegistersResponse) {
                ReadMultipleRegistersResponse readResponse = (ReadMultipleRegistersResponse) response;
                int value = readResponse.getRegisterValue(0);
                logger.info("Successfully read from register {}: {}", address, value);
                return value;
            }
            throw new IOException("Unexpected response type");
        } catch (Exception e) {
            logger.error("Error reading from register {}", address, e);
            throw new IOException("Failed to read from register", e);
        }
    }

    @Scheduled(fixedRate = 5000)
    public void readAndPublishTemperature() {
        try {
            if (!isConnectedAndReady()) {
                return;
            }

            Optional<Address> addressOpt = addressRepository.findByAddressName("temperature_read");
            if (addressOpt.isEmpty()) {
                logger.error("Temperature read address not found");
                return;
            }

            int modbusAddress = addressOpt.get().getModbusAddress();
            int temperature = readHoldingRegister(modbusAddress);

            Map<String, Object> temperatureData = new HashMap<>();
            temperatureData.put("value", temperature);
            temperatureData.put("timestamp", Instant.now());
            temperatureData.put("deviceId", "Temperature_Sensor");
            temperatureData.put("masterId", this.slaveId);

            mqttService.publish("plc/temperature/value", temperatureData);
            logger.info("Published temperature value: {}", temperature);

        } catch (Exception e) {
            logger.error("Failed to read temperature", e);
        }
    }


    private ModbusResponse executeTransaction(ModbusRequest request) throws Exception {
        ModbusTCPTransaction transaction = new ModbusTCPTransaction(connection);
        transaction.setRequest(request);
        transaction.execute();
        return transaction.getResponse();
    }

    @PostConstruct
    public void subscribeToCommands() {
        try {
            logger.info("Starting to subscribe to MQTT commands...");

            // Add connection test
            if (mqttService == null) {
                logger.error("MQTT Service is null!");
                return;
            }

            // Connection Commands Subscription
            mqttService.subscribe("plc/commands/connect", (topic, message) -> {
                logger.info("Received message on topic {}: {}", topic, new String(message.getPayload()));
                try {
                    Map<String, Object> command = new ObjectMapper().readValue(
                            new String(message.getPayload()),
                            HashMap.class
                    );
                    String action = (String) command.get("action");
                    logger.info("Parsed connection command: {}", command);

                    if ("CONNECT_MASTER".equals(action)) {
                        String ipAddress = (String) command.get("ipAddress");
                        logger.info("Attempting to connect to master at IP: {}", ipAddress);
                        connectToMaster(ipAddress);
                        logger.info("Connection attempt completed");
                    }
                    else if ("DISCONNECT_MASTER".equals(action)) {
                        logger.info("Attempting to disconnect from master");
                        disconnectFromMaster();
                        logger.info("Disconnect attempt completed");
                    }
                } catch (Exception e) {
                    logger.error("Error processing connection command: {}", e.getMessage(), e);
                }
            });

            // Blower Commands Subscription
            mqttService.subscribe("plc/commands/blower", (topic, message) -> {
                logger.info("Received message on topic {}: {}", topic, new String(message.getPayload()));
                try {
                    Map<String, Object> command = new ObjectMapper().readValue(
                            new String(message.getPayload()),
                            HashMap.class
                    );
                    String action = (String) command.get("action");
                    logger.info("Parsed blower command: {}", command);

                    if ("TURN_ON_BLOWER".equals(action)) {
                        logger.info("Starting turn on blower sequence");
                        turnOnBlower1();
                        logger.info("Blower turn on sequence completed");
                    }
                    else if ("TURN_OFF_BLOWER".equals(action)) {
                        logger.info("Starting turn off blower sequence");
                        turnOffBlower1();
                        logger.info("Blower turn off sequence completed");
                    }
                } catch (Exception e) {
                    logger.error("Error processing blower command: {}", e.getMessage(), e);
                }
            });

            logger.info("Successfully subscribed to all command topics");
        } catch (Exception e) {
            logger.error("Failed to setup command subscriptions: {}", e.getMessage(), e);
        }
    }

    // Add these methods to MasterServiceImpl.java in the first backend

    private int readHoldingRegisterByName(String addressName) throws Exception {
        Optional<Address> addressOpt = addressRepository.findByAddressName(addressName);
        if (addressOpt.isEmpty()) {
            logger.error("Address {} not found in database", addressName);
            throw new IOException("Address not found in database");
        }

        int modbusAddress = addressOpt.get().getModbusAddress();
        try {
            if (!connection.isConnected() && !manuallyDisconnected) {
                connection.connect();
            }
            ReadMultipleRegistersRequest req = new ReadMultipleRegistersRequest(modbusAddress, 1);
            req.setUnitID(slaveId);
            ModbusResponse response = executeTransaction(req);

            if (response instanceof ReadMultipleRegistersResponse) {
                ReadMultipleRegistersResponse readResponse = (ReadMultipleRegistersResponse) response;
                int value = readResponse.getRegisterValue(0);
                logger.info("Successfully read {} value: {}", addressName, value);
                return value;
            }
            throw new IOException("Unexpected response type");
        } catch (Exception e) {
            logger.error("Error reading {} value", addressName, e);
            throw new IOException("Failed to read register", e);
        }
    }

    private void writeHoldingRegisterByName(String addressName, int value) throws Exception {
        Optional<Address> addressOpt = addressRepository.findByAddressName(addressName);
        if (addressOpt.isEmpty()) {
            logger.error("Address {} not found in database", addressName);
            throw new IOException("Address not found in database");
        }

        int modbusAddress = addressOpt.get().getModbusAddress();
        try {
            if (!connection.isConnected() && !manuallyDisconnected) {
                connection.connect();
            }

            // Create a Register with the value
            SimpleRegister register = new SimpleRegister(value);
            WriteSingleRegisterRequest req = new WriteSingleRegisterRequest(modbusAddress, register);
            req.setUnitID(slaveId);
            ModbusResponse response = executeTransaction(req);

            if (!(response instanceof WriteSingleRegisterResponse)) {
                throw new IOException("Unexpected response type");
            }
            logger.info("Successfully wrote value {} to {}", value, addressName);
        } catch (Exception e) {
            logger.error("Error writing to {}", addressName, e);
            throw new IOException("Failed to write to register", e);
        }
    }

    @Scheduled(fixedRate = 1000)
    public void readAndPublishBlowerParameters() {
        try {
            if (!isConnectedAndReady()) {
                return;
            }

            // Read all blower parameters
            int frequency = readHoldingRegisterByName("blower_frequency_read");
            int ampere = readHoldingRegisterByName("blower_ampere_read");
            int voltage = readHoldingRegisterByName("blower_voltage_read");

            Map<String, Object> blowerData = new HashMap<>();
            blowerData.put("frequency", frequency);
            blowerData.put("ampere", ampere);
            blowerData.put("voltage", voltage);
            blowerData.put("timestamp", Instant.now());
            blowerData.put("deviceId", "Blower_1");
            blowerData.put("masterId", this.slaveId);

            mqttService.publish("plc/blower/parameters", blowerData);
            logger.info("Published blower parameters: {}", blowerData);

        } catch (Exception e) {
            logger.error("Failed to read blower parameters", e);
        }
    }

    @PostConstruct
    public void subscribeToBlowerCommands() {
        // Add this to your existing subscribeToCommands method
        mqttService.subscribe("plc/commands/blower/frequency", (topic, message) -> {
            try {
                Map<String, Object> command = new ObjectMapper().readValue(
                        new String(message.getPayload()),
                        HashMap.class
                );

                Integer frequency = (Integer) command.get("frequency");
                if (frequency != null) {
                    writeHoldingRegisterByName("blower_frequency_set", frequency);
                    logger.info("Set blower frequency to: {}", frequency);
                }
            } catch (Exception e) {
                logger.error("Error processing blower frequency command", e);
            }
        });
    }
}


