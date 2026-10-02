package com.techflag.workdsh.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/** Low-level model transport only: DSH retains tool execution and conversation ownership. */
@Component
public class SpringAiModelClient {
    private final ObjectMapper json;
    public SpringAiModelClient(ObjectMapper json) { this.json = json; }

    private OpenAiApi api(String baseUrl, String supplierKey) {
        String base = baseUrl.replaceAll("/+$", "");
        String path = base.endsWith("/v1") ? "/chat/completions" : "/v1/chat/completions";
        return OpenAiApi.builder().baseUrl(base).completionsPath(path).apiKey(supplierKey).build();
    }

    public OpenAiApi.ChatCompletion complete(String baseUrl, String supplierKey, byte[] body) throws java.io.IOException {
        var request = json.readValue(body, OpenAiApi.ChatCompletionRequest.class);
        return api(baseUrl, supplierKey).chatCompletionEntity(request).getBody();
    }

    public Flux<OpenAiApi.ChatCompletionChunk> stream(String baseUrl, String supplierKey, byte[] body) throws java.io.IOException {
        var request = json.readValue(body, OpenAiApi.ChatCompletionRequest.class);
        return api(baseUrl, supplierKey).chatCompletionStream(request);
    }
    public org.springframework.ai.anthropic.api.AnthropicApi.ChatCompletionResponse completeMessages(
            String baseUrl, String supplierKey, String version, byte[] body) throws java.io.IOException {
        String base = baseUrl.replaceAll("/+$", "");
        if (base.equals("https://api.deepseek.com")) base += "/anthropic";
        String path = base.endsWith("/v1") ? "/messages" : "/v1/messages";
        var api = org.springframework.ai.anthropic.api.AnthropicApi.builder()
                .baseUrl(base).completionsPath(path).apiKey(supplierKey)
                .anthropicVersion(version == null ? "2023-06-01" : version).build();
        var input = json.readTree(body);
        // Anthropic accepts plain text or blocks; Spring AI's DTO requires blocks.
        for (var message : input.path("messages")) {
            if (message.path("content").isTextual()) {
                var blocks = json.createArrayNode();
                blocks.addObject().put("type", "text").put("text", message.path("content").asText());
                ((com.fasterxml.jackson.databind.node.ObjectNode) message).set("content", blocks);
            }
        }
        return api.chatCompletionEntity(json.treeToValue(input,
                org.springframework.ai.anthropic.api.AnthropicApi.ChatCompletionRequest.class)).getBody();
    }
}
