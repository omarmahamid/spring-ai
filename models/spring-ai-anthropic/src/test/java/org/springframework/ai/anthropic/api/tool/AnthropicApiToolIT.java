/*
 * Copyright 2023 - 2024 the original author or authors.
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
package org.springframework.ai.anthropic.api.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.anthropic.api.AnthropicApi.ChatCompletionResponse;
import org.springframework.ai.anthropic.api.AnthropicApi.ChatCompletionRequest;
import org.springframework.ai.anthropic.api.AnthropicApi.ContentBlock;
import org.springframework.ai.anthropic.api.AnthropicApi.ContentBlock.ContentBlockType;
import org.springframework.ai.anthropic.api.AnthropicApi.AnthropicMessage;
import org.springframework.ai.anthropic.api.AnthropicApi.Role;
import org.springframework.ai.anthropic.api.AnthropicApi.Tool;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.util.CollectionUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 *
 * <a href="https://docs.anthropic.com/claude/docs/tool-use-examples">Tool use
 * examples</a> <br/>
 * <a href="https://docs.anthropic.com/claude/docs/tool-use">Tool use (function
 * calling)</a>
 *
 * @author Christian Tzolov
 * @since 1.0.0
 */
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
@SuppressWarnings("null")
public class AnthropicApiToolIT {

	private static final Logger logger = LoggerFactory.getLogger(AnthropicApiLegacyToolIT.class);

	AnthropicApi anthropicApi = new AnthropicApi(System.getenv("ANTHROPIC_API_KEY"));

	public static final ConcurrentHashMap<String, Function> FUNCTIONS = new ConcurrentHashMap<>();

	static {
		FUNCTIONS.put("getCurrentWeather", new MockWeatherService());
	}

	List<Tool> tools = List.of(new Tool("getCurrentWeather",
			"Get the weather in location. Return temperature in 30°F or 30°C format.", ModelOptionsUtils.jsonToMap("""
					{
						"type": "object",
						"properties": {
							"location": {
								"type": "string",
								"description": "The city and state e.g. San Francisco, CA"
							},
							"unit": {
								"type": "string",
								"enum": ["C", "F"]
							}
						},
						"required": ["location", "unit"]
					}
					""")));

	@Test
	void toolCalls() {

		List<AnthropicMessage> messageConversation = new ArrayList<>();

		AnthropicMessage chatCompletionMessage = new AnthropicMessage(List.of(new ContentBlock(
				"What's the weather like in San Francisco, Tokyo, and Paris? Show the temperature in Celsius.")),
				Role.USER);

		messageConversation.add(chatCompletionMessage);

		ResponseEntity<ChatCompletionResponse> chatCompletion = doCall(messageConversation);

		var responseText = chatCompletion.getBody().content().get(0).text();
		logger.info("FINAL RESPONSE: " + responseText);

		assertThat(responseText).contains("15");
		assertThat(responseText).contains("10");
		assertThat(responseText).contains("30");
	}

	private ResponseEntity<ChatCompletionResponse> doCall(List<AnthropicMessage> messageConversation) {

		ChatCompletionRequest chatCompletionRequest = ChatCompletionRequest.builder()
			.withModel(AnthropicApi.ChatModel.CLAUDE_3_OPUS)
			.withMessages(messageConversation)
			.withMaxTokens(1500)
			.withTemperature(0.8f)
			.withTools(tools)
			.build();

		ResponseEntity<ChatCompletionResponse> response = anthropicApi.chatCompletionEntity(chatCompletionRequest);

		List<ContentBlock> toolToUseList = response.getBody()
			.content()
			.stream()
			.filter(c -> c.type() == ContentBlock.ContentBlockType.TOOL_USE)
			.toList();

		if (CollectionUtils.isEmpty(toolToUseList)) {
			return response;
		}
		// Add use tool message to the conversation history
		messageConversation.add(new AnthropicMessage(response.getBody().content(), Role.ASSISTANT));

		List<ContentBlock> toolResults = new ArrayList<>();

		for (ContentBlock toolToUse : toolToUseList) {

			var id = toolToUse.id();
			var name = toolToUse.name();
			var input = toolToUse.input();

			logger.info("FunctionCalls from the LLM: " + name);

			MockWeatherService.Request request = ModelOptionsUtils.mapToClass(input, MockWeatherService.Request.class);

			logger.info("Resolved function request param: " + request);

			Object functionCallResponseData = FUNCTIONS.get(name).apply(request);

			String content = ModelOptionsUtils.toJsonString(functionCallResponseData);

			logger.info("Function response : " + content);

			toolResults.add(new ContentBlock(ContentBlockType.TOOL_RESULT, id, content));
		}

		// Add function response message to the conversation history
		messageConversation.add(new AnthropicMessage(toolResults, Role.USER));

		return doCall(messageConversation);
	}

}
