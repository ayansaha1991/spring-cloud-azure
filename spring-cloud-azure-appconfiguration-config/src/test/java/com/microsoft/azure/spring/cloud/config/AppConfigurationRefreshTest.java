/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See LICENSE in the project root for
 * license information.
 */
package com.microsoft.azure.spring.cloud.config;

import static com.microsoft.azure.spring.cloud.config.Constants.CONFIGURATION_SUFFIX;
import static com.microsoft.azure.spring.cloud.config.Constants.FEATURE_SUFFIX;
import static com.microsoft.azure.spring.cloud.config.TestConstants.TEST_CONN_STRING;
import static com.microsoft.azure.spring.cloud.config.TestConstants.TEST_ETAG;
import static com.microsoft.azure.spring.cloud.config.TestConstants.TEST_STORE_NAME;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.springframework.cloud.endpoint.event.RefreshEvent;
import org.springframework.context.ApplicationEventPublisher;

import com.azure.data.appconfiguration.models.ConfigurationSetting;
import com.microsoft.azure.spring.cloud.config.stores.ClientStore;
import com.microsoft.azure.spring.cloud.config.stores.ConfigStore;

public class AppConfigurationRefreshTest {

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private AppConfigurationProperties properties;

    private ArrayList<ConfigurationSetting> keys;

    @Mock
    private Map<String, List<String>> contextsMap;

    AppConfigurationRefresh configRefresh;

    @Mock
    private Date date;

    @Mock
    private ClientStore clientStoreMock;

    private static final String WATCHED_KEYS = "/application/*";

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        ConfigStore store = new ConfigStore();
        store.setEndpoint(TEST_STORE_NAME);
        store.setConnectionString(TEST_CONN_STRING);
        store.setWatchedKey(WATCHED_KEYS);

        properties = new AppConfigurationProperties();
        properties.setStores(Arrays.asList(store));

        properties.setCacheExpiration(Duration.ofMinutes(-60));

        contextsMap = new ConcurrentHashMap<>();
        contextsMap.put(TEST_STORE_NAME, Arrays.asList(TEST_ETAG));
        keys = new ArrayList<ConfigurationSetting>();
        ConfigurationSetting kvi = new ConfigurationSetting();
        kvi.setKey("fake-etag/application/test.key");
        kvi.setValue("TestValue");
        keys.add(kvi);

        ConfigurationSetting item = new ConfigurationSetting();
        item.setKey("fake-etag/application/test.key");
        item.setETag("fake-etag");

        when(clientStoreMock.watchedKeyNames(Mockito.any(), Mockito.any())).thenReturn(WATCHED_KEYS);

        configRefresh = new AppConfigurationRefresh(properties, contextsMap, clientStoreMock);
        StateHolder.setLoadState(TEST_STORE_NAME, true);
    }

    @After
    public void cleanupMethod() {
        StateHolder.setEtagState(TEST_STORE_NAME + CONFIGURATION_SUFFIX, new ConfigurationSetting());
        StateHolder.setEtagState(TEST_STORE_NAME + FEATURE_SUFFIX, new ConfigurationSetting());
    }

    @Test
    public void nonUpdatedEtagShouldntPublishEvent() throws Exception {
        StateHolder.setEtagState(TEST_STORE_NAME + CONFIGURATION_SUFFIX, initialResponse().get(0));
        StateHolder.setEtagState(TEST_STORE_NAME + FEATURE_SUFFIX, initialResponse().get(0));

        configRefresh.setApplicationEventPublisher(eventPublisher);

        when(clientStoreMock.listSettingRevisons(Mockito.any(), Mockito.anyString())).thenReturn(initialResponse());

        configRefresh.refreshConfigurations().get();
        verify(eventPublisher, times(0)).publishEvent(any(RefreshEvent.class));
    }

    @Test
    public void updatedEtagShouldPublishEvent() throws Exception {
        StateHolder.setEtagState(TEST_STORE_NAME + CONFIGURATION_SUFFIX, initialResponse().get(0));
        StateHolder.setEtagState(TEST_STORE_NAME + FEATURE_SUFFIX, initialResponse().get(0));

        when(clientStoreMock.listSettingRevisons(Mockito.any(), Mockito.anyString())).thenReturn(initialResponse());
        configRefresh.setApplicationEventPublisher(eventPublisher);

        // The first time an action happens it can't update
        assertFalse(configRefresh.refreshConfigurations().get());
        verify(eventPublisher, times(0)).publishEvent(any(RefreshEvent.class));

        when(clientStoreMock.listSettingRevisons(Mockito.any(), Mockito.anyString())).thenReturn(updatedResponse());

        // If there is a change it should update
        assertTrue(configRefresh.refreshConfigurations().get());
        verify(eventPublisher, times(1)).publishEvent(any(RefreshEvent.class));

        StateHolder.setEtagState(TEST_STORE_NAME + CONFIGURATION_SUFFIX, updatedResponse().get(0));
        StateHolder.setEtagState(TEST_STORE_NAME + FEATURE_SUFFIX, updatedResponse().get(0));

        HashMap<String, String> map = new HashMap<String, String>();
        map.put("store1_configuration", "fake-etag-updated");
        map.put("store1_feature", "fake-etag-updated");

        ConfigurationSetting updated = new ConfigurationSetting();
        updated.setETag("fake-etag-updated");

        StateHolder.setEtagState(TEST_STORE_NAME + CONFIGURATION_SUFFIX, updated);

        // If there is no change it shouldn't update
        assertFalse(configRefresh.refreshConfigurations().get());
        verify(eventPublisher, times(1)).publishEvent(any(RefreshEvent.class));
    }

    @Test
    public void noEtagReturned() throws Exception {
        StateHolder.setEtagState(TEST_STORE_NAME + CONFIGURATION_SUFFIX, initialResponse().get(0));
        StateHolder.setEtagState(TEST_STORE_NAME + FEATURE_SUFFIX, initialResponse().get(0));

        when(clientStoreMock.listSettingRevisons(Mockito.any(), Mockito.anyString()))
                .thenReturn(new ArrayList<ConfigurationSetting>());
        configRefresh.setApplicationEventPublisher(eventPublisher);

        // The first time an action happens it can't update
        assertFalse(configRefresh.refreshConfigurations().get());
        verify(eventPublisher, times(0)).publishEvent(any(RefreshEvent.class));
    }

    @Test
    public void nullItemsReturned() throws Exception {
        StateHolder.setEtagState(TEST_STORE_NAME + CONFIGURATION_SUFFIX, initialResponse().get(0));
        StateHolder.setEtagState(TEST_STORE_NAME + FEATURE_SUFFIX, initialResponse().get(0));

        when(clientStoreMock.listSettingRevisons(Mockito.any(), Mockito.anyString())).thenReturn(null);
        configRefresh.setApplicationEventPublisher(eventPublisher);

        // The first time an action happens it can't update
        assertFalse(configRefresh.refreshConfigurations().get());
        verify(eventPublisher, times(0)).publishEvent(any(RefreshEvent.class));
    }

    @Test
    public void noInitialStateNoEtag() throws Exception {
        ConfigStore store = new ConfigStore();
        store.setEndpoint(TEST_STORE_NAME + "_LOST");
        store.setConnectionString(TEST_CONN_STRING);
        store.setWatchedKey(WATCHED_KEYS);
        
        AppConfigurationProperties propertiesLost = new AppConfigurationProperties();
        propertiesLost.setStores(Arrays.asList(store));

        propertiesLost.setCacheExpiration(Duration.ofMinutes(-60));
        AppConfigurationRefresh configRefreshLost = new AppConfigurationRefresh(propertiesLost, contextsMap,
                clientStoreMock);
        StateHolder.setLoadState(TEST_STORE_NAME + "_LOST", true);
        when(clientStoreMock.listSettingRevisons(Mockito.any(), Mockito.anyString())).thenReturn(null);
        configRefreshLost.setApplicationEventPublisher(eventPublisher);

        // The first time an action happens it can't update
        assertFalse(configRefreshLost.refreshConfigurations().get());
        verify(eventPublisher, times(0)).publishEvent(any(RefreshEvent.class));
    }

    @Test
    public void noInitialStateHasEtag() throws Exception {
        ConfigStore store = new ConfigStore();
        store.setEndpoint(TEST_STORE_NAME + "_LOST");
        store.setConnectionString(TEST_CONN_STRING);
        store.setWatchedKey(WATCHED_KEYS);
        
        AppConfigurationProperties propertiesLost = new AppConfigurationProperties();
        propertiesLost.setStores(Arrays.asList(store));

        propertiesLost.setCacheExpiration(Duration.ofMinutes(-60));
        AppConfigurationRefresh configRefreshLost = new AppConfigurationRefresh(propertiesLost, contextsMap,
                clientStoreMock);
        StateHolder.setLoadState(TEST_STORE_NAME + "_LOST", true);
        
        when(clientStoreMock.listSettingRevisons(Mockito.any(), Mockito.anyString())).thenReturn(updatedResponse());
        configRefreshLost.setApplicationEventPublisher(eventPublisher);

        // The first time an action happens it can't update
        assertTrue(configRefreshLost.refreshConfigurations().get());
        verify(eventPublisher, times(1)).publishEvent(any(RefreshEvent.class));
    }

    @Test
    public void notRefreshTime() throws Exception {
        StateHolder.setEtagState(TEST_STORE_NAME + CONFIGURATION_SUFFIX, initialResponse().get(0));
        StateHolder.setEtagState(TEST_STORE_NAME + FEATURE_SUFFIX, initialResponse().get(0));

        properties.setCacheExpiration(Duration.ofMinutes(60));
        AppConfigurationRefresh watchLargeDelay = new AppConfigurationRefresh(properties, contextsMap, clientStoreMock);

        watchLargeDelay.setApplicationEventPublisher(eventPublisher);
        watchLargeDelay.refreshConfigurations().get();

        // The first time an action happens it can update
        verify(eventPublisher, times(0)).publishEvent(any(RefreshEvent.class));
    }

    private List<ConfigurationSetting> initialResponse() {
        ConfigurationSetting item = new ConfigurationSetting();
        item.setETag("fake-etag");

        return Arrays.asList(item);
    }

    private List<ConfigurationSetting> updatedResponse() {
        ConfigurationSetting item = new ConfigurationSetting();
        item.setETag("fake-etag-updated");

        return Arrays.asList(item);
    }

}
