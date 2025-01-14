# Control of test MQTT client(s)

This API enables users to control MQTT clients using gRPC.
For testing purposes control can run as standalone JAR with hardcoded sequence of client's operations which includes: connect, subscribe, publish, unsubscribe, disconnect.

## How to build
To build this control to uber JAR file, use the following command:

```sh
mvn clean license:check checkstyle:check pmd:check package
```

# How to test
To run integrated with sources tests, use the following command:
```sh
mvn -ntp -U clean verify
```

# Standalone application
For manual testing stanalone application is also provided.
When any agent (controlled MQTT client) will connected that application will start hardcoded test scenario which includes connection, subscribe, publish, unsubscribe and disconnect.
Pauses are inserted between these operation. Also receiving of MQTT message supported.

## Settings
### MQTT Client Id
Can be passed via Java system property named `mqtt_client_id` or (when property is missed) from environment variable named `MQTT_CLIENT_ID`.
When both are missing string `MQTT_Client_1` is used.


### MQTT broker address
Can be passed via Java system property named `mqtt_broker_addr` or (when property is missed) from environment variable named `MQTT_BROKER_ADDR`.
When both are missing string `localhost` is used.


### MQTT broker port
Can be passed via Java system property named `mqtt_broker_port` or (when property is missed) from environment variable named `MQTT_BROKER_PORT`.
When both are missing value `8883` is used.


### Client's CA file
Can be passed via Java system property named `mqtt_client_ca_file` or (when property is missed) from environment variable named `MQTT_CLIENT_CA_FILE`.
When both are missing value `ca.crt` is used.


### Client's Certificate file
Can be passed via Java system property named `mqtt_client_cert_file` or (when property is missed) from environment variable named `MQTT_CLIENT_CERT_FILE`.
When both are missing value `client.crt` is used.

### Client's Key file
Can be passed via Java system property named `mqtt_client_key_file` or (when property is missed) from environment variable named `MQTT_CLIENT_KEY_FILE`.
When both are missing value `client.key` is used.

## Arguments
Currently application accept two arguments.

### Port number
Is a first command line argument.
By default control will starting gRPC server on port 47619 and any address.

### Use TLS
Is a second command line argument.
By default control will requires to agent to establish TLS-secured connection with broker.
That can be changes by using false keyword for second argument.

### Use MQTT 5.0
Is a third command line argument.
By default control will requires to agent to establish MQTT 5.0 connection with broker.
That can be changes by using false keyword for third argument. In that case MQTT v3.1.1 will be used.

### Examples
```sh
java -Dmqtt_client_id=client5 -Dmqtt_broker_addr=10.10.10.10 -Dmqtt_broker_port=1883 -Dmqtt_client_ca_file=rootCA.crt -Dmqtt_client_cert_file=client1.crt -Dmqtt_client_key_file=client1.key -jar target/aws-greengrass-testing-mqtt-control.jar 47619 true
```

```sh
MQTT_CLIENT_ID=client5 MQTT_BROKER_ADDR=10.10.10.10 MQTT_BROKER_PORT=1883 MQTT_CLIENT_CA_FILE=rootCA.crt MQTT_CLIENT_CERT_FILE=client1.crt MQTT_CLIENT_KEY_FILE=client1.key java -jar target/aws-greengrass-testing-mqtt-control.jar 47619 true
```

# How to run
To run this control:
1. When going to use TLS connection with broker: upload rootCA.pem, thingCert.crt and privKey.key files in project root directory. It is CA of MQTT broker and client's credentials.
2. Configure client id and addres and port of broker, see `Settings`
3. Run the control
4. Run client(s)
