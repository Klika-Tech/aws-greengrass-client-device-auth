/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.testing.mqtt5.client.sdkmqtt;

import com.aws.greengrass.testing.mqtt5.client.MqttConnection;
import com.aws.greengrass.testing.mqtt5.client.MqttLib;
import com.aws.greengrass.testing.mqtt5.client.exceptions.MqttException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.amazon.awssdk.crt.CRT;
import software.amazon.awssdk.crt.mqtt5.Mqtt5Client;
import software.amazon.awssdk.crt.mqtt5.Mqtt5ClientOptions;
import software.amazon.awssdk.crt.mqtt5.Mqtt5ClientOptions.ClientSessionBehavior;
import software.amazon.awssdk.crt.mqtt5.OnAttemptingConnectReturn;
import software.amazon.awssdk.crt.mqtt5.OnConnectionFailureReturn;
import software.amazon.awssdk.crt.mqtt5.OnConnectionSuccessReturn;
import software.amazon.awssdk.crt.mqtt5.OnDisconnectionReturn;
import software.amazon.awssdk.crt.mqtt5.OnStoppedReturn;
import software.amazon.awssdk.crt.mqtt5.PublishReturn;
import software.amazon.awssdk.crt.mqtt5.packets.ConnectPacket;
import software.amazon.awssdk.crt.mqtt5.packets.DisconnectPacket;
import software.amazon.awssdk.iot.AwsIotMqtt5ClientBuilder;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Interface of MQTT5 connection.
 */
public class MqttConnectionImpl implements MqttConnection {
    private static final Logger logger = LogManager.getLogger(MqttConnectionImpl.class);

    private final AtomicBoolean isClosed = new AtomicBoolean();

    private final ClientsLifecycleEvents lifecycleEvents = new ClientsLifecycleEvents();
    private final ClientsPublishEvents publishEvents = new ClientsPublishEvents();
    private final Mqtt5Client client;

    private class ClientsLifecycleEvents implements Mqtt5ClientOptions.LifecycleEvents {
        CompletableFuture<Void> connectedFuture = new CompletableFuture<>();
        CompletableFuture<Void> stoppedFuture = new CompletableFuture<>();

        @Override
        public void onAttemptingConnect(Mqtt5Client client, OnAttemptingConnectReturn onAttemptingConnectReturn) {
            logger.atInfo().log("Attempting to connect...");
        }

        @Override
        public void onConnectionSuccess(Mqtt5Client client, OnConnectionSuccessReturn onConnectionSuccessReturn) {
            String clientId = onConnectionSuccessReturn.getNegotiatedSettings().getAssignedClientID();
            logger.atInfo().log("Connection success, client id {}", clientId);
            connectedFuture.complete(null);
        }

        @Override
        public void onConnectionFailure(Mqtt5Client client, OnConnectionFailureReturn onConnectionFailureReturn) {
            String errorString = CRT.awsErrorString(onConnectionFailureReturn.getErrorCode());
            logger.atInfo().log("Connection failed with error: {}", errorString);
            connectedFuture.completeExceptionally(new MqttException("Could not connect: " + errorString));
        }

        @Override
        public void onDisconnection(Mqtt5Client client, OnDisconnectionReturn onDisconnectionReturn) {
            logger.atInfo().log("Disconnected");
        }

        @Override
        public void onStopped(Mqtt5Client client, OnStoppedReturn onStoppedReturn) {
            logger.atInfo().log("Stopped");
            stoppedFuture.complete(null);
        }
    }

    private class ClientsPublishEvents implements Mqtt5ClientOptions.PublishEvents {
        @Override
        public void onMessageReceived(Mqtt5Client client, PublishReturn result) {
            logger.atInfo().log("Message received");
        }
    }

    /**
     * Creates a MQTT5 connection.
     *
     * @param connectionParams connection parameters
     * @throws MqttException on errors
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public MqttConnectionImpl(MqttLib.ConnectionParams connectionParams) throws MqttException {
        super();

        client = createClient(connectionParams);
        client.start();
        try {
            lifecycleEvents.connectedFuture.get(connectionParams.getConnectTimeout(), TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new MqttException("Exception occurred during connect", ex);
        }
    }

    /**
     * Close MQTT connection.
     *
     * @param reasonCode reason why connection is closed
     */
    @SuppressWarnings({"PMD.UseTryWithResources", "PMD.AvoidCatchingGenericException"})
    @Override
    public void disconnect(int reasonCode) throws MqttException {
        if (!isClosed.getAndSet(true)) {
            DisconnectPacket.DisconnectPacketBuilder disconnectBuilder = new DisconnectPacket.DisconnectPacketBuilder();
            DisconnectPacket.DisconnectReasonCode disconnectReason
                = DisconnectPacket.DisconnectReasonCode.getEnumValueFromInteger(reasonCode);
            DisconnectPacket disconnectPacket = disconnectBuilder.withReasonCode(disconnectReason).build();
            // TODO: withUserProperties()
            client.stop(disconnectPacket);
            try {
                lifecycleEvents.stoppedFuture.get(60, TimeUnit.SECONDS); // TODO: tune timeout
            } catch (Exception ex) {
                logger.atError().withThrowable(ex).log("Failed during disconnecting from MQTT broker");
                throw new MqttException("Could not disconnect", ex);
            } finally {
                client.close();
            }
        }
    }

    private Mqtt5Client createClient(MqttLib.ConnectionParams connectionParams) {

        try (AwsIotMqtt5ClientBuilder builder = getClientBuilder(connectionParams)) {
            ConnectPacket.ConnectPacketBuilder connectProperties = new ConnectPacket.ConnectPacketBuilder()
                .withClientId(connectionParams.getClientId())
                .withKeepAliveIntervalSeconds(Long.valueOf(connectionParams.getKeepalive()));

            ClientSessionBehavior clientSessionBehavior = connectionParams.isCleanSession()
                        ? ClientSessionBehavior.CLEAN : ClientSessionBehavior.DEFAULT;

            builder.withConnectProperties(connectProperties)
                .withSessionBehavior(clientSessionBehavior)
                .withPort(Long.valueOf(connectionParams.getPort()))
                .withLifeCycleEvents(lifecycleEvents)
                .withPublishEvents(publishEvents);

            // TODO: SDK custom reconnect settings
            return builder.build();
        }
    }

    private AwsIotMqtt5ClientBuilder getClientBuilder(MqttLib.ConnectionParams connectionParams) {
        if (connectionParams.getKey() == null) {
            logger.atInfo().log("Creating Mqtt5Client without TLS");
            return AwsIotMqtt5ClientBuilder.newMqttBuilder(connectionParams.getHost());
        } else {
            logger.atInfo().log("Creating Mqtt5Client with TLS");
            return AwsIotMqtt5ClientBuilder.newDirectMqttBuilderWithMtlsFromMemory(
                    connectionParams.getHost(),
                    connectionParams.getCert(),
                    connectionParams.getKey())
                .withCertificateAuthority(connectionParams.getCa());
        }
    }
}