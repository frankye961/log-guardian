package com.logguardian.ai;

import com.logguardian.ai.model.IncidentSeverity;
import com.logguardian.ai.model.IncidentSummary;
import com.logguardian.ai.model.IncidentSummaryRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
public class AiIncidentSummarizer {

    private final ChatClient chatClient;
    private final String promptTemplate;

    public AiIncidentSummarizer(
            ObjectProvider<ChatClient.Builder> builderProvider,
            @Value("${logguardian.ai.prompt-template}") String promptTemplate
    ) {
        ChatClient.Builder builder = builderProvider.getIfAvailable();
        this.chatClient = builder == null ? null : builder.build();
        this.promptTemplate = promptTemplate;
    }

    public IncidentSummary summarize(IncidentSummaryRequest request) {
        if (chatClient == null) {
            return new IncidentSummary(
                    "AI unavailable",
                    "No chat model is configured for incident summarization.",
                    "Missing ChatModel bean",
                    "Configure Spring AI model credentials or disable AI summarization.",
                    IncidentSeverity.UNKNOWN
            );
        }

        String prompt = buildPrompt(request);
        log.info("Sending AI prompt for fingerprint={} count={}", request.fingerprint(), request.count());

        try {
            String aiResponse = chatClient
                    .prompt()
                    .user(prompt)
                    .call()
                    .content();

            log.info("AI raw response: {}", aiResponse);

            if (aiResponse == null || aiResponse.isBlank()) {
                return new IncidentSummary(
                        "AI Incident Analysis",
                        "No response content returned by model",
                        "Unknown",
                        "Check model configuration and API response",
                        IncidentSeverity.UNKNOWN
                );
            }

            return mapToSummary(aiResponse);
        } catch (Exception e) {
            log.error("AI summarization failed for fingerprint={}", request.fingerprint(), e);
            return new IncidentSummary(
                    "AI Incident Analysis Failed",
                    "The AI model call failed",
                    e.getClass().getSimpleName(),
                    "Check API key, model name, network connectivity, and Spring AI configuration",
                    IncidentSeverity.UNKNOWN
            );
        }
    }

    private String buildPrompt(IncidentSummaryRequest request) {
        return applyTemplate(promptTemplate, Map.of(
                "fingerprint", safe(request.fingerprint()),
                "level", String.valueOf(request.level()),
                "count", String.valueOf(request.count()),
                "sourceId", safe(request.sourceId()),
                "sourceName", safe(request.sourceName()),
                "samples", formatSamples(request)
        ));
    }

    private String formatSamples(IncidentSummaryRequest request) {
        if (request.sampleMessages() == null || request.sampleMessages().isEmpty()) {
            return "No sample logs available";
        }

        StringBuilder builder = new StringBuilder();
        int index = 1;
        for (String msg : request.sampleMessages()) {
            builder.append(index++).append(". ").append(msg).append("\n");
        }
        return builder.toString();
    }

    private String applyTemplate(String template, Map<String, String> values) {
        String resolvedTemplate = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            resolvedTemplate = resolvedTemplate.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return resolvedTemplate;
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private IncidentSummary mapToSummary(String response) {
        return new IncidentSummary(
                "AI Incident Analysis",
                response,
                "See AI analysis",
                "See AI analysis",
                IncidentSeverity.UNKNOWN
        );
    }

    public boolean isAvailable() {
        return chatClient != null;
    }
}
