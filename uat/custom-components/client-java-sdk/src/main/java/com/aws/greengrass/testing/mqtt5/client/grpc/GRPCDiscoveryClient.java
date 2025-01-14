/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.testing.mqtt5.client.grpc;

import com.aws.greengrass.testing.mqtt.client.DiscoveryRequest;
import com.aws.greengrass.testing.mqtt.client.Mqtt5Disconnect;
import com.aws.greengrass.testing.mqtt.client.Mqtt5Message;
import com.aws.greengrass.testing.mqtt.client.Mqtt5Properties;
import com.aws.greengrass.testing.mqtt.client.MqttAgentDiscoveryGrpc;
import com.aws.greengrass.testing.mqtt.client.MqttConnectionId;
import com.aws.greengrass.testing.mqtt.client.MqttQoS;
import com.aws.greengrass.testing.mqtt.client.OnMqttDisconnectRequest;
import com.aws.greengrass.testing.mqtt.client.OnReceiveMessageRequest;
import com.aws.greengrass.testing.mqtt.client.RegisterReply;
import com.aws.greengrass.testing.mqtt.client.RegisterRequest;
import com.aws.greengrass.testing.mqtt.client.UnregisterRequest;
import com.aws.greengrass.testing.mqtt5.client.GRPCClient;
import com.aws.greengrass.testing.mqtt5.client.GRPCClient.MqttReceivedMessage;
import com.aws.greengrass.testing.mqtt5.client.exceptions.GRPCException;
import com.google.protobuf.ByteString;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import lombok.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

/**
 * Implementation of gRPC client used to discover agent.
 */
class GRPCDiscoveryClient implements GRPCClient {
    private static final Logger logger = LogManager.getLogger(GRPCDiscoveryClient.class);

    private static final String GRPC_REQUEST_FAILED = "gRPC request failed";

    private final String agentId;
    private final ManagedChannel channel;
    private final MqttAgentDiscoveryGrpc.MqttAgentDiscoveryBlockingStub blockingStub;

    /**
     * Create instance of gRPC client for agent discovery.
     *
     * @param agentId id of agent to identify control channel by gRPC server
     * @param address address of gRPC service
     * @throws GRPCException on errors
     */
    GRPCDiscoveryClient(@NonNull String agentId, @NonNull String address) throws GRPCException {
        super();
        this.agentId = agentId;
        this.channel = Grpc.newChannelBuilder(address, InsecureChannelCredentials.create()).build();
        this.blockingStub = MqttAgentDiscoveryGrpc.newBlockingStub(channel);
    }

    /**
     * Register the agent.
     *
     * @return IP address of client as visible by server
     * @throws GRPCException on errors
     */
    String registerAgent() throws GRPCException {
        RegisterRequest request = RegisterRequest.newBuilder()
                                        .setAgentId(agentId)
                                        .build();
        RegisterReply reply;

        try {
            reply = blockingStub.registerAgent(request);
        } catch (StatusRuntimeException ex) {
            logger.atError().withThrowable(ex).log(GRPC_REQUEST_FAILED);
            throw new GRPCException(ex);
        }
        return reply.getAddress();
    }

    /**
     * Discover the agent.
     *
     * @param address host of local gRPC service
     * @param port port of local gRPC service
     */
    void discoveryAgent(String address, int port) throws GRPCException {
        DiscoveryRequest request = DiscoveryRequest.newBuilder()
                                        .setAgentId(agentId)
                                        .setAddress(address)
                                        .setPort(port)
                                        .build();
        try {
            blockingStub.discoveryAgent(request);
        } catch (StatusRuntimeException ex) {
            logger.atError().withThrowable(ex).log(GRPC_REQUEST_FAILED);
            throw new GRPCException(ex);
        }
    }

    /**
     * Unregister agent.
     *
     * @param reason reason of unregistering
     * @throws GRPCException on errors
     */
    void unregisterAgent(String reason) throws GRPCException {
        UnregisterRequest request = UnregisterRequest.newBuilder()
                                        .setAgentId(agentId)
                                        .setReason(reason)
                                        .build();
        try {
            blockingStub.unregisterAgent(request);
        } catch (StatusRuntimeException ex) {
            logger.atError().withThrowable(ex).log(GRPC_REQUEST_FAILED);
            throw new GRPCException(ex);
        }
    }


    @Override
    public void onReceiveMqttMessage(int connectionId, MqttReceivedMessage message) {
        Mqtt5Message.Builder builder = Mqtt5Message.newBuilder()
                                        .setTopic(message.getTopic())
                                        .setPayload(ByteString.copyFrom(message.getPayload()))
                                        .setQos(MqttQoS.forNumber(message.getQos()))
                                        .setRetain(message.isRetain());

        // please use the same order as in 3.3.2 PUBLISH Variable Header of MQTT v5.0 specification
        final Boolean payloadFormatIndicator = message.getPayloadFormatIndicator();
        if (payloadFormatIndicator != null) {
            builder.setPayloadFormatIndicator(payloadFormatIndicator);
        }

        final Integer messageExpiryInterval = message.getMessageExpiryInterval();
        if (messageExpiryInterval != null) {
            builder.setMessageExpiryInterval(messageExpiryInterval);
        }

        final String responseTopic = message.getResponseTopic();
        if (responseTopic != null) {
            builder.setResponseTopic(responseTopic);
        }

        final byte[] correlationData = message.getCorrelationData();
        if (correlationData != null) {
            builder.setCorrelationData(ByteString.copyFrom(correlationData));
        }

        final List<Mqtt5Properties> userProperties = message.getUserProperties();
        if (userProperties != null && !userProperties.isEmpty()) {
            builder.addAllProperties(userProperties);
        }

        final String contentType = message.getContentType();
        if (contentType != null) {
            builder.setContentType(contentType);
        }

        OnReceiveMessageRequest request = OnReceiveMessageRequest.newBuilder()
                        .setAgentId(agentId)
                        .setConnectionId(MqttConnectionId.newBuilder().setConnectionId(connectionId).build())
                        .setMsg(builder.build())
                        .build();
        try {
            blockingStub.onReceiveMessage(request);
        } catch (StatusRuntimeException ex) {
            logger.atError().withThrowable(ex).log(GRPC_REQUEST_FAILED);
        }
    }

    @Override
    public void onMqttDisconnect(int connectionId, DisconnectInfo disconnectInfo, String error) {
        OnMqttDisconnectRequest.Builder builder = OnMqttDisconnectRequest.newBuilder()
                        .setAgentId(agentId)
                        .setConnectionId(MqttConnectionId.newBuilder().setConnectionId(connectionId).build());
        if (disconnectInfo != null) {
            Mqtt5Disconnect.Builder disconnectBuilder = Mqtt5Disconnect.newBuilder();

            final Integer reasonCode = disconnectInfo.getReasonCode();
            if (reasonCode != null) {
                disconnectBuilder.setReasonCode(reasonCode);
            }

            final Integer sessionExpiryInterval = disconnectInfo.getSessionExpiryInterval();
            if (sessionExpiryInterval != null) {
                disconnectBuilder.setSessionExpiryInterval(sessionExpiryInterval);
            }

            final String reasonString = disconnectInfo.getReasonString();
            if (reasonString != null) {
                disconnectBuilder.setReasonString(reasonString);
            }

            final String serverReference = disconnectInfo.getServerReference();
            if (serverReference != null) {
                disconnectBuilder.setServerReference(serverReference);
            }

            final List<Mqtt5Properties> userProperties = disconnectInfo.getUserProperties();
            if (userProperties != null && !userProperties.isEmpty()) {
                disconnectBuilder.addAllProperties(userProperties);
            }

            builder.setDisconnect(disconnectBuilder.build());
        }

        if (error != null) {
            builder.setError(error);
        }

        try {
            blockingStub.onMqttDisconnect(builder.build());
        } catch (StatusRuntimeException ex) {
            logger.atError().withThrowable(ex).log(GRPC_REQUEST_FAILED);
        }
    }

    /**
     * Closes the gRPC client.
     */
    void close() {
        channel.shutdown();
    }
}
