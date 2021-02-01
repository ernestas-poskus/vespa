// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.container.handler.metrics;

import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.yahoo.container.jdisc.RequestHandlerTestDriver;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.yahoo.container.handler.metrics.MetricsV2Handler.V2_PATH;
import static com.yahoo.container.handler.metrics.MetricsV2Handler.VALUES_PATH;
import static com.yahoo.container.handler.metrics.MetricsV2Handler.consumerQuery;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * @author gjoranv
 */
public class MetricsV2HandlerTest {

    private static final String URI_BASE = "http://localhost";

    private static final String V2_URI = URI_BASE + V2_PATH;
    private static final String VALUES_URI = URI_BASE + VALUES_PATH;

    // Mock applicationmetrics api
    private static final String MOCK_METRICS_PATH = "/node0";

    private static final String TEST_FILE = "application-metrics.json";
    private static final String RESPONSE = getFileContents(TEST_FILE);
    private static final String CPU_METRIC = "cpu.util";
    private static final String REPLACED_CPU_METRIC = "replaced_cpu_util";
    private static final String CUSTOM_CONSUMER = "custom-consumer";

    private static RequestHandlerTestDriver testDriver;

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(options().dynamicPort());

    @Before
    public void setup() {
        setupWireMock();
        var handler = new MetricsV2Handler(Executors.newSingleThreadExecutor(),
                                           new MetricsProxyApiConfig.Builder()
                                                   .metricsPort(wireMockRule.port())
                                                   .metricsApiPath(MOCK_METRICS_PATH)
                                                   .prometheusApiPath("Not/In/Use")
                                                   .build());
        testDriver = new RequestHandlerTestDriver(handler);
    }

    private void setupWireMock() {
        wireMockRule.stubFor(get(urlPathEqualTo(MOCK_METRICS_PATH))
                                     .willReturn(aResponse().withBody(RESPONSE)));

        // Add a slightly different response for a custom consumer.
        String myConsumerResponse = RESPONSE.replaceAll(CPU_METRIC, REPLACED_CPU_METRIC);
        wireMockRule.stubFor(get(urlPathEqualTo(MOCK_METRICS_PATH))
                                     .withQueryParam("consumer", equalTo(CUSTOM_CONSUMER))
                                     .willReturn(aResponse().withBody(myConsumerResponse)));
    }

    @Test
    public void v2_response_contains_values_uri() throws Exception {
        String response = testDriver.sendRequest(V2_URI).readAll();
        JSONObject root = new JSONObject(response);
        assertTrue(root.has("resources"));

        JSONArray resources = root.getJSONArray("resources");
        assertEquals(1, resources.length());

        JSONObject valuesUri = resources.getJSONObject(0);
        assertEquals(VALUES_URI, valuesUri.getString("url"));
    }

    @Ignore
    @Test
    public void visually_inspect_values_response() throws Exception {
        JSONObject responseJson = getResponseAsJson(null);
        System.out.println(responseJson.toString(4));
    }

    @Test
    public void invalid_path_yields_error_response() throws Exception {
        String response = testDriver.sendRequest(V2_URI + "/invalid").readAll();
        JSONObject root = new JSONObject(response);
        assertTrue(root.has("error"));
        assertTrue(root.getString("error" ).startsWith("No content"));
    }

    @Test
    public void values_response_is_equal_to_test_file() {
        String response = testDriver.sendRequest(VALUES_URI).readAll();
        assertEquals(RESPONSE, response);
    }

    @Test
    public void consumer_is_propagated_to_metrics_proxy_api() throws JSONException {
        JSONObject responseJson = getResponseAsJson(CUSTOM_CONSUMER);

        JSONObject firstNodeMetricsValues =
                responseJson.getJSONArray("nodes").getJSONObject(0)
                        .getJSONObject("node")
                        .getJSONArray("metrics").getJSONObject(0)
                        .getJSONObject("values");

        assertTrue(firstNodeMetricsValues.has(REPLACED_CPU_METRIC));
    }

    private JSONObject getResponseAsJson(String consumer) {
        String response = testDriver.sendRequest(VALUES_URI + consumerQuery(consumer)).readAll();
        try {
            return new JSONObject(response);
        } catch (JSONException e) {
            fail("Failed to create json object: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    static String getFileContents(String filename) {
        InputStream in = MetricsV2HandlerTest.class.getClassLoader().getResourceAsStream(filename);
        if (in == null) {
            throw new RuntimeException("File not found: " + filename);
        }
        return new BufferedReader(new InputStreamReader(in)).lines().collect(Collectors.joining("\n"));
    }

}
