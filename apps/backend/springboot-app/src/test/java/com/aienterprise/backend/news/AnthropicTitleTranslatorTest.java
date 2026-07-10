package com.aienterprise.backend.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AnthropicTitleTranslatorTest {

    private static final String MESSAGES_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL = "claude-opus-4-8";

    private static Article article(String title, String link) {
        return new Article(title, link, "Example Wire", null, null, null, null);
    }

    @Test
    void translatesAllTitlesInOneBatchedMessagesCall() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(MESSAGES_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-api-key", "test-key"))
                .andExpect(header("anthropic-version", "2023-06-01"))
                .andExpect(jsonPath("$.model").value(MODEL))
                .andExpect(jsonPath("$.max_tokens").value(160))
                .andExpect(jsonPath("$.messages[0].content", containsString("Model beats benchmark")))
                .andExpect(jsonPath("$.messages[0].content", containsString("Second story")))
                .andRespond(withSuccess("""
                        {
                          "content": [{"type": "text",
                                       "text": "[\\"모델이 벤치마크를 넘어서다\\", \\"두 번째 이야기\\"]"}],
                          "stop_reason": "end_turn"
                        }
                        """, MediaType.APPLICATION_JSON));

        List<Article> out = new AnthropicTitleTranslator(builder, "test-key", MODEL)
                .translateTitles(List.of(
                        article("Model beats benchmark", "https://a"),
                        article("Second story", "https://b")));

        server.verify();
        assertThat(out).hasSize(2);
        assertThat(out.get(0).translatedTitle()).isEqualTo("모델이 벤치마크를 넘어서다");
        assertThat(out.get(1).translatedTitle()).isEqualTo("두 번째 이야기");
        // Originals stay intact.
        assertThat(out.get(0).title()).isEqualTo("Model beats benchmark");
        assertThat(out.get(0).link()).isEqualTo("https://a");
    }

    @Test
    void emptyInputMakesNoApiCall() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // No expectations registered — any request would fail the test.

        List<Article> out = new AnthropicTitleTranslator(builder, "test-key", MODEL)
                .translateTitles(List.of());

        server.verify();
        assertThat(out).isEmpty();
    }

    @Test
    void returnsArticlesUnchangedWhenApiFails() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(MESSAGES_URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        List<Article> in = List.of(article("Model beats benchmark", "https://a"));
        List<Article> out = new AnthropicTitleTranslator(builder, "test-key", MODEL)
                .translateTitles(in);

        assertThat(out).isEqualTo(in);
    }

    @Test
    void returnsArticlesUnchangedWhenTranslationCountMismatches() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(MESSAGES_URL))
                .andRespond(withSuccess(
                        "{\"content\": [{\"type\": \"text\", \"text\": \"[\\\"하나뿐\\\"]\"}]}",
                        MediaType.APPLICATION_JSON));

        List<Article> in = List.of(
                article("Model beats benchmark", "https://a"),
                article("Second story", "https://b"));
        List<Article> out = new AnthropicTitleTranslator(builder, "test-key", MODEL)
                .translateTitles(in);

        assertThat(out).isEqualTo(in);
    }
}
