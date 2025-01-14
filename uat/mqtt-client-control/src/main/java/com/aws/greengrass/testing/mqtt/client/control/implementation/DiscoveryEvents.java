/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.testing.mqtt.client.control.implementation;

import com.aws.greengrass.testing.mqtt.client.Mqtt5Disconnect;
import com.aws.greengrass.testing.mqtt.client.Mqtt5Message;
import lombok.NonNull;

/**
 * Interface of receiver for events from dicovery service.
 */
public interface DiscoveryEvents {
    /**
     * Called when new agent has been attached and discovered.
     *
     * @param agentId id of the agent
     * @param address address of gRPC service of the agent
     * @param port port of of gRPC service of the agent
     */
    void onDiscoveryAgent(@NonNull String agentId, @NonNull String address, int port);

    /**
     * Called when agent calls unregister.
     *
     * @param agentId agent identification string
     */
    void onUnregisterAgent(@NonNull String agentId);

    /**
     * Called when MQTT message has been received.
     *
     * @param agentId agent identification string
     * @param connectionId id of connected where receives message
     * @param message the received MQTT message
     */
    void onMessageReceived(@NonNull String agentId, int connectionId, @NonNull Mqtt5Message message);

    /**
     * Called when MQTT connection has been disconnected.
     *
     * @param agentId agent identification string
     * @param connectionId id of connected where receives message
     * @param disconnect optional infomation from DISCONNECT packet
     * @param error optional OS-dependent error string
     */
    void onMqttDisconnect(@NonNull String agentId, int connectionId, @NonNull Mqtt5Disconnect disconnect, String error);
}
