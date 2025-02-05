/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

#ifndef MOSQUITTO_TEST_CLIENT_MQTTEXCEPTION_H
#define MOSQUITTO_TEST_CLIENT_MQTTEXCEPTION_H

#include "ClientException.h"

/**
 * MqttException class represent exceptions on MQTT side.
 */
class MqttException : public ClientException {
public:

    /**
     * Constructor of MqttException.
     *
     * @param message the message of exception
     * @param code the error code
     */
    MqttException(const char * message, int code)
        : ClientException(message, code) {}
    ~MqttException() {}
};

#endif /* MOSQUITTO_TEST_CLIENT_MQTTEXCEPTION_H */
