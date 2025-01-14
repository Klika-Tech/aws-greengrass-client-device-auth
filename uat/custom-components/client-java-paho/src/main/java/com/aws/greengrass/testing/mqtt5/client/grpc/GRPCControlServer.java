/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.testing.mqtt5.client.grpc;

import com.aws.greengrass.testing.mqtt.client.Empty;
import com.aws.greengrass.testing.mqtt.client.Mqtt5ConnAck;
import com.aws.greengrass.testing.mqtt.client.Mqtt5Message;
import com.aws.greengrass.testing.mqtt.client.Mqtt5Properties;
import com.aws.greengrass.testing.mqtt.client.Mqtt5Subscription;
import com.aws.greengrass.testing.mqtt.client.MqttClientControlGrpc;
import com.aws.greengrass.testing.mqtt.client.MqttCloseRequest;
import com.aws.greengrass.testing.mqtt.client.MqttConnectReply;
import com.aws.greengrass.testing.mqtt.client.MqttConnectRequest;
import com.aws.greengrass.testing.mqtt.client.MqttConnectionId;
import com.aws.greengrass.testing.mqtt.client.MqttProtoVersion;
import com.aws.greengrass.testing.mqtt.client.MqttPublishReply;
import com.aws.greengrass.testing.mqtt.client.MqttPublishRequest;
import com.aws.greengrass.testing.mqtt.client.MqttSubscribeReply;
import com.aws.greengrass.testing.mqtt.client.MqttSubscribeRequest;
import com.aws.greengrass.testing.mqtt.client.MqttUnsubscribeRequest;
import com.aws.greengrass.testing.mqtt.client.ShutdownRequest;
import com.aws.greengrass.testing.mqtt.client.TLSSettings;
import com.aws.greengrass.testing.mqtt5.client.GRPCClient;
import com.aws.greengrass.testing.mqtt5.client.MqttConnection;
import com.aws.greengrass.testing.mqtt5.client.MqttLib;
import com.aws.greengrass.testing.mqtt5.client.exceptions.MqttException;
import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Implementation of gRPC server handles requests of OTF.
 */
class GRPCControlServer {
    private static final Logger logger = LogManager.getLogger(GRPCControlServer.class);

    private static final String CONNECTION_WITH_DOES_NOT_FOUND = "connection with id {} doesn't found";
    private static final String CONNECTION_DOES_NOT_FOUND = "connection doesn't found";
    private static final int RETAIN_HANDLING_MIN = 0;
    private static final int RETAIN_HANDLING_MAX = 2;
    private static final int TIMEOUT_MIN = 1;

    private static final int PORT_MIN = 1;
    private static final int PORT_MAX = 65_535;
    private static final int QOS_MIN = 0;
    private static final int QOS_MAX = 2;
    private static final int KEEPALIVE_OFF = 0;
    private static final int KEEPALIVE_MIN = 5;
    private static final int KEEPALIVE_MAX = 65_535;

    private static final int REASON_MIN = 0;
    private static final int REASON_MAX = 255;

    private final GRPCClient client;
    private final Server server;
    private final int boundPort;

    private MqttLib mqttLib;
    private String shutdownReason;


    /**
     * MQTT client control service implementation.
     */
    class MqttClientControlImpl extends MqttClientControlGrpc.MqttClientControlImplBase {

        /**
         * Handler of ShutdownAgent gRPC call.
         *
         * @param request incoming request
         * @param responseObserver response control
         */
        @Override
        public void shutdownAgent(ShutdownRequest request, StreamObserver<Empty> responseObserver) {
            // save reason
            shutdownReason = request.getReason();

            // log an event
            logger.atInfo().log("shutdownAgent: reason '{}'", shutdownReason);

            Empty reply = Empty.newBuilder().build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();

            // request server to shutdown
            server.shutdown();
        }

        /**
         * Handler of CreateMqttConnection gRPC call.
         *
         * @param request incoming request
         * @param responseObserver response control
         */
        @SuppressWarnings("PMD.CognitiveComplexity")
        @Override
        public void createMqttConnection(MqttConnectRequest request,
                                         StreamObserver<MqttConnectReply> responseObserver) {

            String clientId = request.getClientId();
            if (clientId == null || clientId.isEmpty()) {
                logger.atWarn().log("empty clientId");
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("empty clientId")
                        .asRuntimeException());
                return;
            }

            String host = request.getHost();
            if (host == null || host.isEmpty()) {
                logger.atWarn().log("empty host");
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("empty host")
                        .asRuntimeException());
                return;
            }

            int port = request.getPort();
            if (port < PORT_MIN || port > PORT_MAX) {
                logger.atWarn().log("invalid port {}, must be in range [{}, {}]", port, PORT_MIN, PORT_MAX);
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("invalid port, must be in range [1, 65535]")
                        .asRuntimeException());
                return;
            }

            MqttProtoVersion version = request.getProtocolVersion();
            if (version != MqttProtoVersion.MQTT_PROTOCOL_V_311 && version != MqttProtoVersion.MQTT_PROTOCOL_V_50) {
                logger.atWarn().log("invalid protocolVersion {}, {} and {} are only supported",
                        version,
                        MqttProtoVersion.MQTT_PROTOCOL_V_311,
                        MqttProtoVersion.MQTT_PROTOCOL_V_50);
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("invalid protocolVersion, only "
                                + "MQTT_PROTOCOL_V_311 and MQTT_PROTOCOL_V_50 are supported")
                        .asRuntimeException());
                return;
            }

            int keepalive = request.getKeepalive();
            if (keepalive != KEEPALIVE_OFF && (keepalive < KEEPALIVE_MIN || keepalive > KEEPALIVE_MAX)) {
                logger.atWarn().log("invalDid keepalive {}, must be in range [{}, {}]",
                        keepalive, KEEPALIVE_MIN, KEEPALIVE_MAX);
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("invalid keepalive, must be in range [1, 65535]")
                        .asRuntimeException());
                return;
            }

            int timeout = request.getTimeout();
            if (!isValidTimeout(timeout, "connect", responseObserver)) {
                return;
            }

            MqttLib.ConnectionParams.ConnectionParamsBuilder connectionParamsBuilder
                    = MqttLib.ConnectionParams.builder()
                    .clientId(clientId)
                    .host(host)
                    .port(port)
                    .keepalive(keepalive)
                    .cleanSession(request.getCleanSession())
                    .mqtt50(version == MqttProtoVersion.MQTT_PROTOCOL_V_50)
                    .connectionTimeout(timeout);

            if (request.getPropertiesList() != null && !request.getPropertiesList().isEmpty()) {
                connectionParamsBuilder.userProperties(request.getPropertiesList());
            }

            if (request.hasRequestResponseInformation()) {
                connectionParamsBuilder.requestResponseInformation(request.getRequestResponseInformation());
            }

            // check TLS optional settings
            if (request.hasTls()) {
                TLSSettings tls = request.getTls();
                List<String> caList = tls.getCaListList();

                if (caList == null || caList.isEmpty()) {
                    logger.atWarn().log("empty CA list");
                    responseObserver.onError(Status.INVALID_ARGUMENT
                            .withDescription("empty CA list")
                            .asRuntimeException());
                    return;
                }

                String cert = tls.getCert();
                if (cert == null || cert.isEmpty()) {
                    logger.atWarn().log("empty certificate");
                    responseObserver.onError(Status.INVALID_ARGUMENT
                            .withDescription("empty certificate")
                            .asRuntimeException());
                    return;
                }

                String key = tls.getKey();
                if (key == null || key.isEmpty()) {
                    logger.atWarn().log("empty private key");
                    responseObserver.onError(Status.INVALID_ARGUMENT
                            .withDescription("empty private key")
                            .asRuntimeException());
                    return;
                }

                connectionParamsBuilder.ca(caList).cert(cert).key(key);
            }

            logger.atInfo().log("createMqttConnection: clientId {} broker {}:{}", clientId, host, port);
            MqttConnectReply.Builder builder = MqttConnectReply.newBuilder();

            AtomicReference<Integer> connectionId = new AtomicReference<>();
            MqttLib.ConnectionParams connectionParams = connectionParamsBuilder.build();
            try {
                MqttConnection connection = mqttLib.createConnection(connectionParams, client);
                connectionId.set(mqttLib.registerConnection(connection));

                MqttConnection.ConnectResult connectResult = connection.start(connectionParams, connectionId.get());
                if (connectResult != null) {
                    builder.setConnectionId(MqttConnectionId.newBuilder().setConnectionId(connectionId.get()).build());
                    convertConnectResult(connectResult, builder);
                    connectionId.set(null);
                }
            } catch (MqttException ex) {
                logger.atWarn().withThrowable(ex).log("Exception during connect");
                responseObserver.onError(ex);
                return;
            } finally {
                Integer id = connectionId.getAndSet(null);
                if (id != null) {
                    mqttLib.unregisterConnection(id);
                }
            }

            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        }

        /**
         * Handler of PublishMqtt gRPC call.
         *
         * @param request incoming request
         * @param responseObserver response control
         */
        @Override
        public void publishMqtt(MqttPublishRequest request, StreamObserver<MqttPublishReply> responseObserver) {

            Mqtt5Message message = request.getMsg();

            int qos = message.getQosValue();
            if (!isValidQos(qos, responseObserver)) {
                return;
            }

            String topic = message.getTopic();
            if (!isValidTopic(topic, responseObserver)) {
                return;
            }

            int timeout = request.getTimeout();
            if (!isValidTimeout(timeout, "publish", responseObserver)) {
                return;
            }

            int connectionId = request.getConnectionId().getConnectionId();
            MqttConnection connection = mqttLib.getConnection(connectionId);
            if (!isValidConnection(connection, connectionId, responseObserver)) {
                return;
            }

            boolean isRetain = message.getRetain();
            logger.atInfo().log("Publish: connectionId {} topic '{}' QoS {} retain {}",
                    connectionId, topic, qos, isRetain);

            MqttConnection.Message.MessageBuilder internalMessage = MqttConnection.Message.builder()
                    .qos(qos)
                    .retain(isRetain)
                    .topic(topic)
                    .payload(message.getPayload().toByteArray());

            if (message.hasPayloadFormatIndicator()) {
                internalMessage.payloadFormatIndicator(message.getPayloadFormatIndicator());
            }

            if (message.hasMessageExpiryInterval()) {
                internalMessage.messageExpiryInterval(message.getMessageExpiryInterval());
            }

            if (message.hasResponseTopic()) {
                internalMessage.responseTopic(message.getResponseTopic());
            }

            if (message.hasCorrelationData()) {
                internalMessage.correlationData(message.getCorrelationData().toByteArray());
            }

            if (message.getPropertiesList() != null && !message.getPropertiesList().isEmpty()) {
                internalMessage.userProperties(message.getPropertiesList());
            }

            if (message.hasContentType()) {
                internalMessage.contentType(message.getContentType());
            }

            MqttPublishReply publishReply;
            try {
                publishReply = connection.publish(timeout, internalMessage.build());
                if (publishReply != null) {
                    logger.atInfo().log("Publish response: connectionId {} reason code {} reason string '{}'",
                            connectionId, publishReply.getReasonCode(), publishReply.getReasonString());
                }
            } catch (MqttException e) {
                logger.atError().withThrowable(e).log("exception during publish");
                responseObserver.onError(e);
                return;
            }

            responseObserver.onNext(publishReply);
            responseObserver.onCompleted();
        }

        /**
         * Handler of CloseMqttConnection gRPC call.
         *
         * @param request incoming request
         * @param responseObserver response control
         */
        @Override
        public void closeMqttConnection(MqttCloseRequest request, StreamObserver<Empty> responseObserver) {

            int reason = request.getReason();
            if (reason < REASON_MIN || reason > REASON_MAX) {
                logger.atWarn().log("invalid reason {}, must be in range [{}, {}]", reason, REASON_MIN, REASON_MAX);
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("invalid reason, must be in range [0, 255]")
                        .asRuntimeException());
                return;
            }

            int timeout = request.getTimeout();
            if (!isValidTimeout(timeout, "disconnect", responseObserver)) {
                return;
            }

            int connectionId = request.getConnectionId().getConnectionId();
            MqttConnection connection = mqttLib.unregisterConnection(connectionId);
            if (!isValidConnection(connection, connectionId, responseObserver)) {
                return;
            }

            logger.atInfo().log("closeMqttConnection: connectionId {} reason {}", connectionId, reason);
            try {
                connection.disconnect(timeout, reason, request.getPropertiesList());
            } catch (MqttException ex) {
                logger.atError().withThrowable(ex).log("exception during disconnect");
                responseObserver.onError(ex);
                return;
            }

            Empty reply = Empty.newBuilder().build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        }

        /**
         * Handler of SubscribeMqtt gRPC call.
         *
         * @param request incoming request
         * @param responseObserver response control
         */
        @SuppressWarnings("PMD.CognitiveComplexity")
        @Override
        public void subscribeMqtt(MqttSubscribeRequest request, StreamObserver<MqttSubscribeReply> responseObserver) {

            int timeout = request.getTimeout();
            if (!isValidTimeout(timeout, "subscribe", responseObserver)) {
                return;
            }

            if (request.hasSubscriptionId()) {
                logger.warn("paho-agent doesn't support getSubscriptionId {}", request.getSubscriptionId());
            }

            List<Mqtt5Subscription> subscriptions = request.getSubscriptionsList();
            if (subscriptions.isEmpty()) {
                logger.atWarn().log("empty subscriptions list");
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("empty subscriptions list")
                        .asRuntimeException());
                return;
            }

            List<MqttConnection.Subscription> outSubscriptions = new ArrayList<>();
            int index = 0;
            for (Mqtt5Subscription subscription : subscriptions) {
                String filter = subscription.getFilter();
                if (filter == null || filter.isEmpty()) {
                    logger.atWarn().log("empty filter at subscription index {}", index);
                    responseObserver.onError(Status.INVALID_ARGUMENT
                            .withDescription("empty filter")
                            .asRuntimeException());
                    return;
                }

                int qos = subscription.getQosValue();
                if (qos < QOS_MIN || qos > QOS_MAX) {
                    logger.atWarn().log("invalid QoS {} at subscription index {}, must be in range [{},{}]",
                            qos, index, QOS_MIN, QOS_MAX);
                    responseObserver.onError(Status.INVALID_ARGUMENT
                            .withDescription("invalid QoS, must be in range [0,2]")
                            .asRuntimeException());
                    return;
                }

                int retainHandling = subscription.getRetainHandlingValue();
                if (retainHandling < RETAIN_HANDLING_MIN || retainHandling > RETAIN_HANDLING_MAX) {
                    logger.atWarn().log("invalid retainHandling {} at subscription index {}, must be in range [{},{}]",
                            qos, index, RETAIN_HANDLING_MIN, RETAIN_HANDLING_MAX);
                    responseObserver.onError(Status.INVALID_ARGUMENT
                            .withDescription("invalid retainHandling, must be in range [0,2]")
                            .asRuntimeException());
                    return;
                }

                boolean noLocal = subscription.getNoLocal();
                boolean retainAsPublished = subscription.getRetainAsPublished();
                MqttConnection.Subscription tmp = new MqttConnection.Subscription(filter, qos, noLocal,
                        retainAsPublished, retainHandling);
                logger.atInfo().log("Subscription: filter '{}' QoS {} noLocal {} retainAsPublished {} "
                                 + "retainHandling {}", filter, qos, noLocal, retainAsPublished, retainHandling);
                outSubscriptions.add(tmp);
                index++;
            }

            int connectionId = request.getConnectionId().getConnectionId();
            MqttConnection connection = mqttLib.getConnection(connectionId);
            if (!isValidConnection(connection, connectionId, responseObserver)) {
                return;
            }

            logger.atInfo().log("Subscribe: connectionId {} for {} filters",
                    connectionId, outSubscriptions.size());
            List<Mqtt5Properties> userProperties = request.getPropertiesList();
            MqttSubscribeReply subscribeReply;
            try {
                subscribeReply = connection.subscribe(timeout, outSubscriptions, userProperties);
                if (subscribeReply != null) {
                    logger.atInfo().log("Subscribe response: connectionId {} reason codes {} reason string '{}'",
                            connectionId, subscribeReply.getReasonCodesList(), subscribeReply.getReasonString());
                }
            } catch (MqttException e) {
                logger.atError().withThrowable(e).log("exception during subscribe");
                responseObserver.onError(e);
                return;
            }

            responseObserver.onNext(subscribeReply);
            responseObserver.onCompleted();
        }

        /**
         * Handler of UnsubscribeMqtt gRPC call.
         *
         * @param request incoming request
         * @param responseObserver response control
         */
        @Override
        public void unsubscribeMqtt(MqttUnsubscribeRequest request,
                                    StreamObserver<MqttSubscribeReply> responseObserver) {

            int timeout = request.getTimeout();
            if (!isValidTimeout(timeout, "unsubscribe", responseObserver)) {
                return;
            }

            List<String> filters = request.getFiltersList();
            if (filters.isEmpty()) {
                logger.atWarn().log("empty filters list");
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("empty filters list")
                        .asRuntimeException());
                return;
            }

            int connectionId = request.getConnectionId().getConnectionId();
            MqttConnection connection = mqttLib.getConnection(connectionId);
            if (!isValidConnection(connection, connectionId, responseObserver)) {
                return;
            }

            logger.atInfo().log("Unsubscribe: connectionId {} for {} filters",
                    connectionId, filters);
            List<Mqtt5Properties> userProperties = request.getPropertiesList();
            MqttSubscribeReply unsubscribeReply;
            try {
                unsubscribeReply = connection.unsubscribe(timeout, filters, userProperties);
                if (unsubscribeReply != null) {
                    logger.atInfo().log("Unsubscribe response: connectionId {} reason codes {} reason string '{}'",
                            connectionId, unsubscribeReply.getReasonCodesList(), unsubscribeReply.getReasonString());
                }
            } catch (MqttException e) {
                logger.atError().withThrowable(e).log("exception during unsubscribe");
                responseObserver.onError(e);
                return;
            }

            responseObserver.onNext(unsubscribeReply);
            responseObserver.onCompleted();
        }

    }

    /**
     * Create instance of gRPC server.
     *
     * @param client reference to gRPC client
     * @param host bind address
     * @param port bind port, or 0 to autoselect
     * @throws IOException on errors
     */
    public GRPCControlServer(@NonNull GRPCClient client, @NonNull String host, int port) throws IOException {
        super();
        this.client = client;

        // TODO: Java implementation of gRPC server has no usage of host
        this.server = Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create())
                .addService(new MqttClientControlImpl())
                .build()
                .start();

        this.boundPort = server.getPort();
        logger.atInfo().log("GRPCControlServer created and listed on {}:{}", host, boundPort);
    }

    /**
     * Gets actual port where gRPC server is bound.
     *
     * @return actual bound port
     */
    public int getPort() {
        return boundPort;
    }

    /**
     * Returns shutdown reason if has been shutdown by party requests.
     *
     * @return reason of shutdown or null when shutdown was not initiated by party
     */
    public String getShutdownReason() {
        return shutdownReason;
    }

    /**
     * Handle gRPC requests and wait to server shutdown.
     *
     * @param mqttLib reference to MQTT side of the client to handler incoming requests
     */
    public void waiting(MqttLib mqttLib) throws InterruptedException {
        this.mqttLib = mqttLib;
        logger.atInfo().log("Server awaitTermination");
        server.awaitTermination();
        logger.atInfo().log("Server awaitTermination done");
    }

    /**
     * Closes the gRPC server.
     */
    public void close() {
        server.shutdown(); // or shutdownNow() ?
    }


    private static void convertConnectResult(MqttConnection.ConnectResult connectResult,
                                             MqttConnectReply.Builder builder) {
        builder.setConnected(connectResult.isConnected());

        String error = connectResult.getError();
        if (error != null) {
            builder.setError(error);
        }

        MqttConnection.ConnAckInfo connAckInfo = connectResult.getConnAckInfo();
        if (connAckInfo != null) {
            builder.setConnAck(convertConnAckInfo(connAckInfo));
        }
    }

    @SuppressWarnings("PMD.CognitiveComplexity")
    private static Mqtt5ConnAck convertConnAckInfo(MqttConnection.ConnAckInfo conAckInfo) {
        final Mqtt5ConnAck.Builder builder = Mqtt5ConnAck.newBuilder();

        Boolean sessionPresent = conAckInfo.getSessionPresent();
        if (sessionPresent != null) {
            builder.setSessionPresent(sessionPresent);
        }

        Integer reasonCode = conAckInfo.getReasonCode();
        if (reasonCode != null) {
            builder.setReasonCode(reasonCode);
        }

        Integer sessionExpiryInterval = conAckInfo.getSessionExpiryInterval();
        if (sessionExpiryInterval != null) {
            builder.setSessionExpiryInterval(sessionExpiryInterval);
        }

        Integer receiveMaximum = conAckInfo.getReceiveMaximum();
        if (receiveMaximum != null) {
            builder.setReceiveMaximum(receiveMaximum);
        }

        Integer maximumQoS = conAckInfo.getMaximumQoS();
        if (maximumQoS != null) {
            builder.setMaximumQoS(maximumQoS);
        }

        Boolean retainAvailable = conAckInfo.getRetainAvailable();
        if (retainAvailable != null) {
            builder.setRetainAvailable(retainAvailable);
        }

        Integer maximumPacketSize = conAckInfo.getMaximumPacketSize();
        if (maximumPacketSize != null) {
            builder.setMaximumPacketSize(maximumPacketSize);
        }

        String assignedClientId = conAckInfo.getAssignedClientId();
        if (assignedClientId != null) {
            builder.setAssignedClientId(assignedClientId);
        }

        String reasonString = conAckInfo.getReasonString();
        if (reasonString != null) {
            builder.setReasonString(reasonString);
        }

        Boolean wildcardSubscriptionsAvailable = conAckInfo.getWildcardSubscriptionsAvailable();
        if (wildcardSubscriptionsAvailable != null) {
            builder.setWildcardSubscriptionsAvailable(wildcardSubscriptionsAvailable);
        }

        Boolean subscriptionIdentifiersAvailable = conAckInfo.getSubscriptionIdentifiersAvailable();
        if (subscriptionIdentifiersAvailable != null) {
            builder.setSubscriptionIdentifiersAvailable(subscriptionIdentifiersAvailable);
        }

        Boolean sharedSubscriptionsAvailable = conAckInfo.getSharedSubscriptionsAvailable();
        if (sharedSubscriptionsAvailable != null) {
            builder.setSharedSubscriptionsAvailable(sharedSubscriptionsAvailable);
        }

        Integer serverKeepAlive = conAckInfo.getServerKeepAlive();
        if (serverKeepAlive != null) {
            builder.setServerKeepAlive(serverKeepAlive);
        }

        String responseInformation = conAckInfo.getResponseInformation();
        if (responseInformation != null) {
            builder.setResponseInformation(responseInformation);
        }

        String serverReference = conAckInfo.getServerReference();
        if (serverReference != null) {
            builder.setServerReference(serverReference);
        }

        List<Mqtt5Properties> userProperties = conAckInfo.getUserProperties();
        if (userProperties != null) {
            builder.addAllProperties(userProperties);
        }

        return builder.build();
    }

    private boolean isValidQos(int qos, StreamObserver<MqttPublishReply> responseObserver) {
        if (qos < QOS_MIN || qos > QOS_MAX) {
            logger.atWarn().log("invalid QoS {}, must be in range [{},{}]", qos, QOS_MIN, QOS_MAX);
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("invalid QoS, must be in range [0,2]")
                    .asRuntimeException());
            return false;
        }
        return true;
    }

    private boolean isValidTopic(String topic, StreamObserver<MqttPublishReply> responseObserver) {
        if (topic == null || topic.isEmpty()) {
            logger.atWarn().log("empty topic");
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("empty topic")
                    .asRuntimeException());
            return false;
        }
        return true;
    }

    private boolean isValidTimeout(int timeout, String command, StreamObserver responseObserver) {
        if (timeout < TIMEOUT_MIN) {
            logger.atWarn().log("invalid {} timeout {}, must be >= {}", command, timeout, TIMEOUT_MIN);
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("invalid ".concat(command).concat(" timeout, must be >= 1"))
                    .asRuntimeException());
            return false;
        }
        return true;
    }

    private boolean isValidConnection(MqttConnection connection, int connectionId, StreamObserver responseObserver) {
        if (connection == null) {
            logger.atWarn().log(CONNECTION_WITH_DOES_NOT_FOUND, connectionId);
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription(CONNECTION_DOES_NOT_FOUND)
                    .asRuntimeException());
            return false;
        }
        return true;
    }
}
