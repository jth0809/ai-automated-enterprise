package com.aienterprise.backend.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AnthropicSummarizerTest {

    private static final String MESSAGES_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL = "claude-opus-4-8";

    private static Article article() {
        return new Article(
                "Model beats benchmark",
                "https://news.example/a",
                "Example Wire",
                "2026-07-08T09:00:00Z",
                "A longer excerpt of the article body.",
                null);
    }

    @Test
    void summarizesArticleViaClaudeMessagesApi() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(MESSAGES_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-api-key", "test-key"))
                .andExpect(header("anthropic-version", "2023-06-01"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.model").value(MODEL))
                .andExpect(jsonPath("$.max_tokens").isNumber())
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[0].content",
                        containsString("Model beats benchmark")))
                .andRespond(withSuccess("""
                        {
                          "content": [{"type": "text", "text": "A crisp two-sentence summary."}],
                          "stop_reason": "end_turn"
                        }
                        """, MediaType.APPLICATION_JSON));

        Article result = new AnthropicSummarizer(builder, "test-key", MODEL).summarize(article());

        server.verify();
        assertThat(result.summary()).isEqualTo("A crisp two-sentence summary.");
        // Everything except the summary is untouched.
        assertThat(result.withSummary(null)).isEqualTo(article());
    }

    @Test
    void returnsArticleUnchangedWhenApiFails() {
        // Rate limits / outages must degrade gracefully: summary stays null
        // and the UI falls back to the excerpt (DisabledSummarizer contract).
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(MESSAGES_URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        Article result = new AnthropicSummarizer(builder, "test-key", MODEL).summarize(article());

        assertThat(result).isEqualTo(article());
    }

    @Test
    void returnsArticleUnchangedWhenResponseHasNoTextBlock() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(MESSAGES_URL))
                .andRespond(withSuccess("{\"content\": []}", MediaType.APPLICATION_JSON));

        Article result = new AnthropicSummarizer(builder, "test-key", MODEL).summarize(article());

        assertThat(result).isEqualTo(article());
    }
}
