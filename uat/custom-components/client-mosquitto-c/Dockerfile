# container to build agent
FROM public.ecr.aws/lts/ubuntu:22.04_stable AS builder
LABEL Description="Mosquitto MQTT client build environment"

ENV HOME /root
SHELL ["/bin/bash", "-c"]
ENV REQUIREMENTS \
    build-essential \
    gcc \
    cmake \
    git \
    autoconf \
    libtool \
    pkg-config \
    libmosquitto-dev

RUN apt-get clean && \
    apt-get update && \
    apt-get -y install --reinstall ca-certificates && \
    apt-get -y --no-install-recommends install $REQUIREMENTS && \
    apt-get clean

COPY src            /usr/src/aws-greengrass-client-device-auth/uat/custom-components/client-mosquitto-c/src
COPY CMakeLists.txt /usr/src/aws-greengrass-client-device-auth/uat/custom-components/client-mosquitto-c/CMakeLists.txt
COPY proto          /usr/src/aws-greengrass-client-device-auth/uat/proto

RUN set -ex; \
    cd /usr/src/aws-greengrass-client-device-auth/uat/custom-components/client-mosquitto-c && \
    CXXFLAGS="-Wall -Wextra -O2" cmake -Bbuild -H. && \
    cmake --build build -j `nproc` --target all && \
    cmake --install build --prefix "/usr/local"

# Define the default command
CMD ["bash"]


# container to run agent
FROM public.ecr.aws/lts/ubuntu:22.04_stable AS runner
LABEL Description="Mosquitto MQTT client runtime environment"

ENV HOME /root
SHELL ["/bin/bash", "-c"]

COPY --from=builder /usr/local/sbin/mosquitto-test-client /usr/local/sbin/mosquitto-test-client
COPY --from=builder /usr/lib/x86_64-linux-gnu/libmosquitto.so.1 /usr/lib/x86_64-linux-gnu/libmosquitto.so.1
COPY --from=builder /usr/lib/x86_64-linux-gnu/libmosquitto.so.2.0.11 /usr/lib/x86_64-linux-gnu/libmosquitto.so.2.0.11

ENTRYPOINT [ "/usr/local/sbin/mosquitto-test-client" ]
CMD [ "mosquitto_agent", "47619", "172.17.0.1", "127.0.0.1" ]
