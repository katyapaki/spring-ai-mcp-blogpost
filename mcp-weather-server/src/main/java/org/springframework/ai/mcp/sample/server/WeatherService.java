/*
* Copyright 2025 - 2025 the original author or authors.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* https://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.springframework.ai.mcp.sample.server;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpProgressToken;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageRequest;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageResult;
import io.modelcontextprotocol.spec.McpSchema.LoggingLevel;
import io.modelcontextprotocol.spec.McpSchema.LoggingMessageNotification;
import io.modelcontextprotocol.spec.McpSchema.ModelPreferences;
import io.modelcontextprotocol.spec.McpSchema.ProgressNotification;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.SamplingMessage;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

/**
 * @author Christian Tzolov
 */
@Service
public class WeatherService {

	private static final Logger logger = LoggerFactory.getLogger(WeatherService.class);

	private final RestClient restClient;

	private final ObjectMapper objectMapper;

	@Value("${weather.sampling.enabled:false}")
	private boolean samplingEnabled;

	@Value("${weather.api.url:https://wttr.in/{latitude},{longitude}?format=j1}")
	private String weatherApiUrl;

	public WeatherService(@Value("${weather.api.connect-timeout-ms:3000}") int connectTimeoutMs,
			@Value("${weather.api.read-timeout-ms:5000}") int readTimeoutMs, ObjectMapper objectMapper) {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(connectTimeoutMs);
		requestFactory.setReadTimeout(readTimeoutMs);
		this.restClient = RestClient.builder().requestFactory(requestFactory).build();
		this.objectMapper = objectMapper;
	}

	/**
	 * The response format from the wttr.in API.
	 */
	public record WeatherResponse(List<CurrentCondition> current_condition) {
		public record CurrentCondition(String temp_C, LocalDateTime localObsDateTime) {
		}
	}

	@McpTool(description = "Get the temperature (in celsius) for a specific location")
	public String getTemperature(McpSyncServerExchange exchange,
			@McpToolParam(description = "The location latitude") Number latitude,
			@McpToolParam(description = "The location longitude") Number longitude,
			@McpProgressToken Object progressToken) {

		double latitudeValue = latitude.doubleValue();
		double longitudeValue = longitude.doubleValue();

		exchange.loggingNotification(LoggingMessageNotification.builder()
			.level(LoggingLevel.DEBUG)
			.data("Call getTemperature Tool with latitude: " + latitudeValue + " and longitude: " + longitudeValue)
			.meta(Map.of()) // non null meata as a workaround for bug: ...
			.build());

		// 0% progress
		exchange.progressNotification(new ProgressNotification(progressToken, 0.0, 1.0, "Retrieving weather forecast"));

		WeatherResponse weatherResponse;
		double temperatureC;
		try {
			String weatherPayload = restClient.get()
				.uri(weatherApiUrl, latitudeValue, longitudeValue)
				.accept(MediaType.APPLICATION_JSON)
				.retrieve()
				.body(String.class);

			weatherResponse = objectMapper.readValue(weatherPayload, WeatherResponse.class);

			temperatureC = Double.parseDouble(weatherResponse.current_condition().get(0).temp_C());
		}
		catch (Exception e) {
			logger.warn("getTemperature: weather provider call failed", e);
			exchange.progressNotification(
					new ProgressNotification(progressToken, 1.0, 1.0, "Weather provider unavailable"));
			return """
					Weather service unavailable right now.
					Unable to reach weather provider for latitude: %s, longitude: %s.
					""".formatted(latitudeValue, longitudeValue);
		}

		String epicPoem = "Sampling is disabled or unsupported by the client.";
		boolean clientSupportsSampling = exchange.getClientCapabilities().sampling() != null;

		logger.info("getTemperature: samplingEnabled={}, clientSupportsSampling={}, progressTokenType={}",
				samplingEnabled, clientSupportsSampling,
				(progressToken != null ? progressToken.getClass().getName() : "null"));

		if (samplingEnabled && clientSupportsSampling) {

			// 50% progress
			exchange.progressNotification(new ProgressNotification(progressToken, 0.5, 1.0, "Start sampling"));

			String samplingMessage = """
					For a weather forecast (temperature is in Celsius): %s.
					At location with latitude: %s and longitude: %s.
					Please write an epic poem about this forecast using a Shakespearean style.
					""".formatted(temperatureC, latitudeValue, longitudeValue);

			try {
				logger.info("getTemperature: sending createMessage sampling request");

				CreateMessageResult samplingResponse = exchange.createMessage(CreateMessageRequest.builder()
					.systemPrompt("You are a poet!")
					.messages(List.of(new SamplingMessage(Role.USER, new TextContent(samplingMessage))))
					.modelPreferences(ModelPreferences.builder().addHint("anthropic").build())
					.maxTokens(512)
					.build());

				epicPoem = ((TextContent) samplingResponse.content()).text();
				logger.info("getTemperature: sampling response received");
			}
			catch (Exception e) {
				logger.warn("getTemperature: sampling request failed, continuing without sampled poem", e);
			}
		}
		else {
			logger.info("getTemperature: skipping sampling branch");

		}

		// 100% progress
		exchange.progressNotification(new ProgressNotification(progressToken, 1.0, 1.0, "Task completed"));

		return """
				Weather Poem2: %s
				about the weather: %s°C at location with latitude: %s and longitude: %s
				""".formatted(epicPoem, temperatureC, latitudeValue, longitudeValue);
	}

}