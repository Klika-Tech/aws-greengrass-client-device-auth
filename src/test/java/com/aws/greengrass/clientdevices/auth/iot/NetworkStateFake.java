/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.clientdevices.auth.iot;

import com.aws.greengrass.clientdevices.auth.infra.NetworkStateProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class NetworkStateFake implements NetworkStateProvider {
    private final List<Consumer<ConnectionState>> handlers = new ArrayList<>();
    private NetworkStateProvider.ConnectionState connectionState;

    @Override
    public void registerHandler(Consumer<ConnectionState> networkChangeHandler) {
        handlers.add(networkChangeHandler);
    }

    @Override
    public ConnectionState getConnectionState() {
        return this.connectionState;
    }

    public void goOnline() {
        this.connectionState = ConnectionState.NETWORK_UP;
        emit();
    }

    public void goOffline() {
        this.connectionState = ConnectionState.NETWORK_DOWN;
        emit();
    }

    private void emit() {
        handlers.forEach((handler) -> {
            handler.accept(connectionState);
        });
    }
}
