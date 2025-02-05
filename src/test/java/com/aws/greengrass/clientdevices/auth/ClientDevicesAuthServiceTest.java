/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.clientdevices.auth;

import com.aws.greengrass.clientdevices.auth.api.UseCases;
import com.aws.greengrass.clientdevices.auth.certificate.CertificateExpiryMonitor;
import com.aws.greengrass.clientdevices.auth.certificate.CertificateHelper;
import com.aws.greengrass.clientdevices.auth.certificate.usecases.ConfigureCustomCertificateAuthority;
import com.aws.greengrass.clientdevices.auth.certificate.usecases.ConfigureManagedCertificateAuthority;
import com.aws.greengrass.clientdevices.auth.configuration.CAConfiguration;
import com.aws.greengrass.clientdevices.auth.configuration.ConfigurationFormatVersion;
import com.aws.greengrass.clientdevices.auth.configuration.GroupConfiguration;
import com.aws.greengrass.clientdevices.auth.configuration.GroupManager;
import com.aws.greengrass.clientdevices.auth.configuration.Permission;
import com.aws.greengrass.clientdevices.auth.configuration.RuntimeConfiguration;
import com.aws.greengrass.clientdevices.auth.exception.AuthorizationException;
import com.aws.greengrass.clientdevices.auth.exception.CloudServiceInteractionException;
import com.aws.greengrass.clientdevices.auth.exception.UseCaseException;
import com.aws.greengrass.componentmanager.KernelConfigResolver;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.mqttclient.spool.SpoolerStoreException;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.GreengrassServiceClientFactory;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import org.hamcrest.collection.IsIterableContainingInAnyOrder;
import org.hamcrest.collection.IsMapContaining;
import org.hamcrest.collection.IsMapWithSize;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.greengrassv2data.GreengrassV2DataClient;
import software.amazon.awssdk.services.greengrassv2data.model.PutCertificateAuthoritiesRequest;
import software.amazon.awssdk.services.greengrassv2data.model.ResourceNotFoundException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStoreException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.aws.greengrass.clientdevices.auth.ClientDevicesAuthService.MAX_ACTIVE_AUTH_TOKENS_TOPIC;
import static com.aws.greengrass.clientdevices.auth.ClientDevicesAuthService.PERFORMANCE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.RUNTIME_STORE_NAMESPACE_TOPIC;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class ClientDevicesAuthServiceTest {
    private static final long TEST_TIME_OUT_SEC = 30L;

    private Kernel kernel;

    @TempDir
    Path rootDir;

    @Mock
    private GroupManager groupManager;

    @Mock
    private GreengrassServiceClientFactory clientFactory;

    @Mock
    private GreengrassV2DataClient client;

    @Mock
    CertificateExpiryMonitor certExpiryMonitor;

    @Captor
    private ArgumentCaptor<GroupConfiguration> configurationCaptor;

    @Captor
    private ArgumentCaptor<PutCertificateAuthoritiesRequest> putCARequestArgumentCaptor;


    @BeforeEach
    void setup(ExtensionContext context) throws DeviceConfigurationException {
        ignoreExceptionOfType(context, SpoolerStoreException.class);

        // Set this property for kernel to scan its own classpath to find plugins
        System.setProperty("aws.greengrass.scanSelfClasspath", "true");
        kernel = new Kernel();
        kernel.getContext().put(GroupManager.class, groupManager);
        kernel.getContext().put(CertificateExpiryMonitor.class, certExpiryMonitor);
        kernel.getContext().put(GreengrassServiceClientFactory.class, clientFactory);

        lenient().when(clientFactory.fetchGreengrassV2DataClient()).thenReturn(client);
    }

    @AfterEach
    void cleanup() {
        kernel.shutdown();
    }

    private void startNucleusWithConfig(String configFileName) throws InterruptedException {
        startNucleusWithConfig(configFileName, State.RUNNING);
    }


    private void startNucleusWithConfig(String configFileName, State expectedServiceState) throws InterruptedException {
        CountDownLatch authServiceRunning = new CountDownLatch(1);
        kernel.parseArgs("-r", rootDir.toAbsolutePath().toString(), "-i",
                getClass().getResource(configFileName).toString());
        kernel.getContext().addGlobalStateChangeListener((service, was, newState) -> {
            if (ClientDevicesAuthService.CLIENT_DEVICES_AUTH_SERVICE_NAME.equals(service.getName())
                    && service.getState().equals(expectedServiceState)) {
                authServiceRunning.countDown();
            }
        });
        kernel.launch();
        assertThat(authServiceRunning.await(TEST_TIME_OUT_SEC, TimeUnit.SECONDS), is(true));
    }

    @Test
    void GIVEN_no_group_configuration_WHEN_start_service_change_THEN_empty_configuration_model_instantiated()
            throws Exception {
        startNucleusWithConfig("emptyGroupConfig.yaml");

        verify(groupManager).setGroupConfiguration(configurationCaptor.capture());
        GroupConfiguration groupConfiguration = configurationCaptor.getValue();
        assertThat(groupConfiguration.getDefinitions(), IsMapWithSize.anEmptyMap());
        assertThat(groupConfiguration.getPolicies(), IsMapWithSize.anEmptyMap());
    }

    @Test
    void GIVEN_bad_group_configuration_WHEN_start_service_THEN_service_in_error_state(ExtensionContext context)
            throws Exception {
        ignoreExceptionOfType(context, IllegalArgumentException.class);
        ignoreExceptionOfType(context, UnrecognizedPropertyException.class);

        startNucleusWithConfig("badGroupConfig.yaml", State.ERRORED);

        verify(groupManager, never()).setGroupConfiguration(any());
    }

    @Test
    void GIVEN_valid_group_configuration_WHEN_start_service_THEN_instantiated_configuration_model_updated()
            throws Exception {
        startNucleusWithConfig("config.yaml");

        verify(groupManager).setGroupConfiguration(configurationCaptor.capture());
        GroupConfiguration groupConfiguration = configurationCaptor.getValue();
        assertThat(groupConfiguration.getFormatVersion(), is(ConfigurationFormatVersion.MAR_05_2021));
        assertThat(groupConfiguration.getDefinitions(), IsMapWithSize.aMapWithSize(2));
        assertThat(groupConfiguration.getPolicies(), IsMapWithSize.aMapWithSize(1));
        assertThat(groupConfiguration.getDefinitions(), IsMapContaining.hasEntry(is("myTemperatureSensors"),
                hasProperty("policyName", is("sensorAccessPolicy"))));
        assertThat(groupConfiguration.getDefinitions(),
                IsMapContaining.hasEntry(is("myHumiditySensors"), hasProperty("policyName", is("sensorAccessPolicy"))));
        assertThat(groupConfiguration.getPolicies(), IsMapContaining.hasEntry(is("sensorAccessPolicy"),
                allOf(IsMapContaining.hasKey("policyStatement1"), IsMapContaining.hasKey("policyStatement2"))));

        Map<String, Set<Permission>> permissionMap = groupConfiguration.getGroupToPermissionsMap();
        assertThat(permissionMap, IsMapWithSize.aMapWithSize(2));

        Permission[] tempSensorPermissions =
                {Permission.builder().principal("myTemperatureSensors").operation("mqtt" + ":connect")
                        .resource("mqtt:clientId:foo").build(),
                        Permission.builder().principal("myTemperatureSensors").operation("mqtt:publish")
                                .resource("mqtt:topic:temperature").build(),
                        Permission.builder().principal("myTemperatureSensors").operation("mqtt:publish")
                                .resource("mqtt:topic:humidity").build()};
        assertThat(permissionMap.get("myTemperatureSensors"), containsInAnyOrder(tempSensorPermissions));
        Permission[] humidSensorPermissions =
                {Permission.builder().principal("myHumiditySensors").operation("mqtt:connect")
                        .resource("mqtt:clientId:foo").build(),
                        Permission.builder().principal("myHumiditySensors").operation("mqtt:publish")
                                .resource("mqtt:topic:temperature").build(),
                        Permission.builder().principal("myHumiditySensors").operation("mqtt:publish")
                                .resource("mqtt:topic:humidity").build()};
        assertThat(permissionMap.get("myHumiditySensors"), containsInAnyOrder(humidSensorPermissions));
    }

    @Test
    void GIVEN_group_has_no_policy_WHEN_start_service_THEN_no_configuration_update(ExtensionContext context)
            throws Exception {
        ignoreExceptionOfType(context, IllegalArgumentException.class);
        ignoreExceptionOfType(context, AuthorizationException.class);

        startNucleusWithConfig("noGroupPolicyConfig.yaml", State.ERRORED);

        verify(groupManager, never()).setGroupConfiguration(any());
    }

    @Test
    void GIVEN_GG_with_cda_WHEN_subscribing_to_ca_updates_THEN_get_list_of_certs() throws Exception {
        startNucleusWithConfig("config.yaml");
        CountDownLatch countDownLatch = new CountDownLatch(1);

        kernel.findServiceTopic(ClientDevicesAuthService.CLIENT_DEVICES_AUTH_SERVICE_NAME)
                .lookup("runtime", "certificates", "authorities").subscribe((why, newv) -> {
                    List<String> caPemList = (List<String>) newv.toPOJO();
                    if (caPemList != null) {
                        Assertions.assertEquals(1, caPemList.size());
                        countDownLatch.countDown();
                    }
                });
        Assertions.assertTrue(countDownLatch.await(TEST_TIME_OUT_SEC, TimeUnit.SECONDS));
    }

    @Test
    void GIVEN_updated_ca_certs_WHEN_updateCACertificateConfig_THEN_cert_topic_updated()
            throws InterruptedException, ServiceLoadException {
        startNucleusWithConfig("config.yaml");

        ClientDevicesAuthService clientDevicesAuthService =
                (ClientDevicesAuthService) kernel.locate(ClientDevicesAuthService.CLIENT_DEVICES_AUTH_SERVICE_NAME);

        List<String> expectedCACerts = new ArrayList<>(Arrays.asList("CA1"));
        clientDevicesAuthService.updateCACertificateConfig(expectedCACerts);
        assertCaCertTopicContains(expectedCACerts);

        expectedCACerts.add("CA2");
        clientDevicesAuthService.updateCACertificateConfig(expectedCACerts);
        assertCaCertTopicContains(expectedCACerts);

        expectedCACerts.remove("CA1");
        expectedCACerts.add("CA3");
        clientDevicesAuthService.updateCACertificateConfig(expectedCACerts);
        assertCaCertTopicContains(expectedCACerts);
    }

    void assertCaCertTopicContains(List<String> expectedCerts) {
        Topic caCertTopic = kernel.findServiceTopic(ClientDevicesAuthService.CLIENT_DEVICES_AUTH_SERVICE_NAME)
                .lookup("runtime", "certificates", "authorities");
        List<String> caPemList = (List<String>) caCertTopic.toPOJO();
        Assertions.assertNotNull(caPemList);
        assertThat(caPemList, IsIterableContainingInAnyOrder.containsInAnyOrder(expectedCerts.toArray()));
    }

    @Test
    void GIVEN_GG_with_cda_WHEN_restart_kernel_THEN_ca_is_persisted()
            throws InterruptedException, CertificateEncodingException, KeyStoreException, IOException,
            ServiceLoadException {
        startNucleusWithConfig("config.yaml");

        String initialPassphrase = getCaPassphrase();
        Assertions.assertNotNull(initialPassphrase);
        List<String> initialCerts = getCaCertificates();
        assertThat(initialCerts, is(not(empty())));

        kernel.shutdown();
        kernel = new Kernel().parseArgs("-r", rootDir.toAbsolutePath().toString());
        kernel.getContext().put(CertificateExpiryMonitor.class, certExpiryMonitor);
        kernel.getContext().put(GreengrassServiceClientFactory.class, clientFactory);
        startNucleusWithConfig("config.yaml");

        String finalPassphrase = getCaPassphrase();
        Assertions.assertNotNull(finalPassphrase);
        List<String> finalCerts = getCaCertificates();
        assertThat(finalCerts, is(not(empty())));

        assertThat(initialPassphrase, is(finalPassphrase));
        assertThat(initialCerts, is(finalCerts));
    }

    private String getCaPassphrase() {
        Topic caPassphraseTopic = kernel.findServiceTopic(ClientDevicesAuthService.CLIENT_DEVICES_AUTH_SERVICE_NAME)
                .lookup(RUNTIME_STORE_NAMESPACE_TOPIC, RuntimeConfiguration.CA_PASSPHRASE_KEY);
        return (String) caPassphraseTopic.toPOJO();
    }

    private List<String> getCaCertificates()
            throws ServiceLoadException, CertificateEncodingException, KeyStoreException, IOException {
        ClientDevicesAuthService clientDevicesAuthService =
                (ClientDevicesAuthService) kernel.locate(ClientDevicesAuthService.CLIENT_DEVICES_AUTH_SERVICE_NAME);
        return clientDevicesAuthService.getCertificateManager().getCACertificates();
    }


    @Test
    void GIVEN_GG_with_cda_WHEN_updated_ca_type_THEN_ca_is_updated()
            throws InterruptedException, ServiceLoadException, KeyStoreException, CertificateException, IOException {
        startNucleusWithConfig("config.yaml");

        List<String> initialCACerts = getCaCertificates();
        X509Certificate initialCA = pemToX509Certificate(initialCACerts.get(0));
        assertThat(initialCA.getSigAlgName(), is(CertificateHelper.RSA_SIGNING_ALGORITHM));
        String initialCaPassPhrase = getCaPassphrase();

        kernel.getContext().runOnPublishQueueAndWait(() -> {
            kernel.locate(ClientDevicesAuthService.CLIENT_DEVICES_AUTH_SERVICE_NAME).getConfig()
                    .lookup(KernelConfigResolver.CONFIGURATION_CONFIG_KEY, CAConfiguration.CERTIFICATE_AUTHORITY_TOPIC,
                            CAConfiguration.CA_TYPE_KEY);
        });
        Topic topic = kernel.locate(ClientDevicesAuthService.CLIENT_DEVICES_AUTH_SERVICE_NAME).getConfig()
                .lookup(KernelConfigResolver.CONFIGURATION_CONFIG_KEY, CAConfiguration.CERTIFICATE_AUTHORITY_TOPIC,
                        CAConfiguration.CA_TYPE_KEY);
        topic.withValue(Collections.singletonList("RSA_2048"));
        // Block until subscriber has finished updating
        kernel.getContext().waitForPublishQueueToClear();

        List<String> secondCACerts = getCaCertificates();
        X509Certificate secondCA = pemToX509Certificate(secondCACerts.get(0));
        assertThat(secondCA.getSigAlgName(), is(CertificateHelper.RSA_SIGNING_ALGORITHM));
        assertThat(initialCA, is(secondCA));
        assertThat(getCaPassphrase(), is(initialCaPassPhrase));

        topic.withValue(Collections.singletonList("ECDSA_P256"));
        // Block until subscriber has finished updating
        kernel.getContext().waitForPublishQueueToClear();

        List<String> thirdCACerts = getCaCertificates();
        X509Certificate thirdCA = pemToX509Certificate(thirdCACerts.get(0));
        assertThat(thirdCA.getSigAlgName(), is(CertificateHelper.ECDSA_SIGNING_ALGORITHM));
        assertThat(initialCA, not(thirdCA));
        assertThat(getCaPassphrase(), not(initialCaPassPhrase));

        verify(client, times(2)).putCertificateAuthorities(putCARequestArgumentCaptor.capture());
        List<List<String>> certificatesInRequests = putCARequestArgumentCaptor.getAllValues().stream()
                .map(PutCertificateAuthoritiesRequest::coreDeviceCertificates).collect(Collectors.toList());
        assertThat(certificatesInRequests, contains(initialCACerts, thirdCACerts));
    }

    @Test
    void GIVEN_certificateAuthorityConfiguration_WHEN_itChanges_THEN_CAisConfigured()
            throws InterruptedException, ServiceLoadException, UseCaseException {
        ArgumentCaptor<CAConfiguration> configurationCaptor = ArgumentCaptor.forClass(CAConfiguration.class);
        UseCases useCasesMock = mock(UseCases.class);
        ConfigureCustomCertificateAuthority customCAUseCase = mock(ConfigureCustomCertificateAuthority.class);
        ConfigureManagedCertificateAuthority managedCAUseCase = mock(ConfigureManagedCertificateAuthority.class);
        when(useCasesMock.get(ConfigureCustomCertificateAuthority.class)).thenReturn(customCAUseCase);
        when(useCasesMock.get(ConfigureManagedCertificateAuthority.class)).thenReturn(managedCAUseCase);
        kernel.getContext().put(UseCases.class, useCasesMock);

        startNucleusWithConfig("config.yaml");
        Topics topics = kernel.locate(ClientDevicesAuthService.CLIENT_DEVICES_AUTH_SERVICE_NAME).getConfig();

        // Block until subscriber has finished updating
        kernel.getContext().waitForPublishQueueToClear();
        verify(managedCAUseCase, times(1)).apply(configurationCaptor.capture());

        topics.lookup(KernelConfigResolver.CONFIGURATION_CONFIG_KEY, CAConfiguration.CERTIFICATE_AUTHORITY_TOPIC,
                CAConfiguration.CA_PRIVATE_KEY_URI).withValue("file:///intermediateCA.key");
        topics.lookup(KernelConfigResolver.CONFIGURATION_CONFIG_KEY, CAConfiguration.CERTIFICATE_AUTHORITY_TOPIC,
                CAConfiguration.CA_CERTIFICATE_URI).withValue("file:///intermediateCA.pem");

        kernel.getContext().waitForPublishQueueToClear();
        verify(managedCAUseCase, times(1)).apply(configurationCaptor.capture());
        verify(customCAUseCase, times(1)).apply(configurationCaptor.capture());

        topics.lookup(KernelConfigResolver.CONFIGURATION_CONFIG_KEY, CAConfiguration.DEPRECATED_CA_TYPE_KEY)
                .withValue(Collections.singletonList("ECDSA_P256"));

        kernel.getContext().waitForPublishQueueToClear();
        verify(managedCAUseCase, times(1)).apply(configurationCaptor.capture());
        verify(customCAUseCase, times(2)).apply(configurationCaptor.capture());

        topics.lookup(KernelConfigResolver.CONFIGURATION_CONFIG_KEY, PERFORMANCE_TOPIC, MAX_ACTIVE_AUTH_TOKENS_TOPIC)
                .withValue(2);

        kernel.getContext().waitForPublishQueueToClear();
        verify(managedCAUseCase, times(1)).apply(configurationCaptor.capture());
        verify(customCAUseCase, times(2)).apply(configurationCaptor.capture());
    }

    @Test
    void GIVEN_GG_with_cda_WHEN_ca_type_provided_in_config_THEN_valid_ca_created()
            throws IOException, InterruptedException, ServiceLoadException, CertificateException, KeyStoreException {
        startNucleusWithConfig("config_with_ec_ca.yaml");

        List<String> initialCACerts = getCaCertificates();
        X509Certificate initialCA = pemToX509Certificate(initialCACerts.get(0));
        assertThat(initialCA.getSigAlgName(), is(CertificateHelper.ECDSA_SIGNING_ALGORITHM));
        verify(client).putCertificateAuthorities(putCARequestArgumentCaptor.capture());
        PutCertificateAuthoritiesRequest request = putCARequestArgumentCaptor.getValue();
        assertThat(request.coreDeviceCertificates(), is(initialCACerts));
    }

    @Test
    void GIVEN_cloud_service_error_WHEN_update_ca_type_THEN_service_in_running_state(ExtensionContext context)
            throws InterruptedException {
        ignoreExceptionOfType(context, ResourceNotFoundException.class);
        ignoreExceptionOfType(context, CloudServiceInteractionException.class);
        when(client.putCertificateAuthorities(any(PutCertificateAuthoritiesRequest.class))).thenThrow(
                ResourceNotFoundException.class);
        startNucleusWithConfig("config_with_ec_ca.yaml", State.RUNNING);
    }

    private X509Certificate pemToX509Certificate(String certPem) throws IOException, CertificateException {
        byte[] certBytes = certPem.getBytes(StandardCharsets.UTF_8);
        CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
        X509Certificate cert;
        try (InputStream certStream = new ByteArrayInputStream(certBytes)) {
            cert = (X509Certificate) certFactory.generateCertificate(certStream);
        }
        return cert;
    }
}