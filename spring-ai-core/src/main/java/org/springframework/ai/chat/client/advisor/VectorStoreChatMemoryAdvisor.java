/*
 * Copyright 2023-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.ai.chat.client.advisor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAroundAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.Content;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * Memory is retrieved from a VectorStore added into the prompt's system text.
 *
 * @author Christian Tzolov
 * @since 1.0.0
 */
public class VectorStoreChatMemoryAdvisor extends AbstractChatMemoryAdvisor<VectorStore> {

	private static final String DOCUMENT_METADATA_CONVERSATION_ID = "conversationId";

	private static final String DOCUMENT_METADATA_MESSAGE_TYPE = "messageType";

	private static final String DEFAULT_SYSTEM_TEXT_ADVISE = """

			Use the long term conversation memory from the LONG_TERM_MEMORY section to provide accurate answers.

			---------------------
			LONG_TERM_MEMORY:
			{long_term_memory}
			---------------------

			""";

	private final String systemTextAdvise;

	public VectorStoreChatMemoryAdvisor(VectorStore vectorStore) {
		this(vectorStore, DEFAULT_SYSTEM_TEXT_ADVISE);
	}

	public VectorStoreChatMemoryAdvisor(VectorStore vectorStore, String systemTextAdvise) {
		super(vectorStore);
		this.systemTextAdvise = systemTextAdvise;
	}

	public VectorStoreChatMemoryAdvisor(VectorStore vectorStore, String defaultConversationId,
			int chatHistoryWindowSize) {
		this(vectorStore, defaultConversationId, chatHistoryWindowSize, DEFAULT_SYSTEM_TEXT_ADVISE);
	}

	public VectorStoreChatMemoryAdvisor(VectorStore vectorStore, String defaultConversationId,
			int chatHistoryWindowSize, String systemTextAdvise) {
		this(vectorStore, defaultConversationId, chatHistoryWindowSize, systemTextAdvise,
				Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER);
	}

	/**
	 * Constructor for VectorStoreChatMemoryAdvisor.
	 * @param vectorStore the vector store instance used for managing and querying
	 * documents.
	 * @param defaultConversationId the default conversation ID used if none is provided
	 * in the context.
	 * @param chatHistoryWindowSize the window size for the chat history retrieval.
	 * @param systemTextAdvise the system text advice used for the chat advisor system.
	 * @param order the order of precedence for this advisor in the chain.
	 */
	public VectorStoreChatMemoryAdvisor(VectorStore vectorStore, String defaultConversationId,
			int chatHistoryWindowSize, String systemTextAdvise, int order) {
		super(vectorStore, defaultConversationId, chatHistoryWindowSize, true, order);
		this.systemTextAdvise = systemTextAdvise;
	}

	public static Builder builder(VectorStore chatMemory) {
		return new Builder(chatMemory);
	}

	@Override
	public AdvisedResponse aroundCall(AdvisedRequest advisedRequest, CallAroundAdvisorChain chain) {

		advisedRequest = this.before(advisedRequest);

		AdvisedResponse advisedResponse = chain.nextAroundCall(advisedRequest);

		this.observeAfter(advisedResponse);

		return advisedResponse;
	}

	@Override
	public Flux<AdvisedResponse> aroundStream(AdvisedRequest advisedRequest, StreamAroundAdvisorChain chain) {

		Flux<AdvisedResponse> advisedResponses = this.doNextWithProtectFromBlockingBefore(advisedRequest, chain,
				this::before);

		// The observeAfter will certainly be executed on non-blocking Threads in case
		// of some models - e.g. when the model client is a WebClient
		return new MessageAggregator().aggregateAdvisedResponse(advisedResponses, this::observeAfter);
	}

	private AdvisedRequest before(AdvisedRequest request) {

		String advisedSystemText = request.systemText() + System.lineSeparator() + this.systemTextAdvise;

		var searchRequest = SearchRequest.query(request.userText())
			.withTopK(this.doGetChatMemoryRetrieveSize(request.adviseContext()))
			.withFilterExpression(DOCUMENT_METADATA_CONVERSATION_ID + "=='"
					+ this.doGetConversationId(request.adviseContext()) + "'");

		List<Document> documents = this.getChatMemoryStore().similaritySearch(searchRequest);

		String longTermMemory = documents.stream()
			.map(Content::getContent)
			.collect(Collectors.joining(System.lineSeparator()));

		Map<String, Object> advisedSystemParams = new HashMap<>(request.systemParams());
		advisedSystemParams.put("long_term_memory", longTermMemory);

		AdvisedRequest advisedRequest = AdvisedRequest.from(request)
			.withSystemText(advisedSystemText)
			.withSystemParams(advisedSystemParams)
			.build();

		UserMessage userMessage = new UserMessage(request.userText(), request.media());
		this.getChatMemoryStore()
			.write(toDocuments(List.of(userMessage), this.doGetConversationId(request.adviseContext())));

		return advisedRequest;
	}

	private void observeAfter(AdvisedResponse advisedResponse) {

		List<Message> assistantMessages = advisedResponse.response()
			.getResults()
			.stream()
			.map(g -> (Message) g.getOutput())
			.toList();

		this.getChatMemoryStore()
			.write(toDocuments(assistantMessages, this.doGetConversationId(advisedResponse.adviseContext())));
	}

	private List<Document> toDocuments(List<Message> messages, String conversationId) {

		List<Document> docs = messages.stream()
			.filter(m -> m.getMessageType() == MessageType.USER || m.getMessageType() == MessageType.ASSISTANT)
			.map(message -> {
				var metadata = new HashMap<>(message.getMetadata() != null ? message.getMetadata() : new HashMap<>());
				metadata.put(DOCUMENT_METADATA_CONVERSATION_ID, conversationId);
				metadata.put(DOCUMENT_METADATA_MESSAGE_TYPE, message.getMessageType().name());
				if (message instanceof UserMessage userMessage) {
					return new Document(userMessage.getContent(), userMessage.getMedia(), metadata);
				}
				else if (message instanceof AssistantMessage assistantMessage) {
					return new Document(assistantMessage.getContent(), metadata);
				}
				throw new RuntimeException("Unknown message type: " + message.getMessageType());
			})
			.toList();

		return docs;
	}

	public static class Builder extends AbstractChatMemoryAdvisor.AbstractBuilder<VectorStore> {

		private String systemTextAdvise = DEFAULT_SYSTEM_TEXT_ADVISE;

		protected Builder(VectorStore chatMemory) {
			super(chatMemory);
		}

		public Builder withSystemTextAdvise(String systemTextAdvise) {
			this.systemTextAdvise = systemTextAdvise;
			return this;
		}

		@Override
		public VectorStoreChatMemoryAdvisor build() {
			return new VectorStoreChatMemoryAdvisor(this.chatMemory, this.conversationId, this.chatMemoryRetrieveSize,
					this.systemTextAdvise);
		}

	}

}