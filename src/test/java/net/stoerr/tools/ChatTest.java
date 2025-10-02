package net.stoerr.tools;

import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.Test;
import dev.langchain4j.model.chat.ChatModel;

/**
 * Test for OpenAI chat functionality using langchain4j
 */
public class ChatTest {

    @Test
    public void testOpenAiChat() {
        String apikey = System.getenv("OPENAI_API_KEY");
        ChatModel chatModel = OpenAiChatModel.builder()
                .apiKey(apikey)
                .modelName("gpt-4.1")
                .build();

        String joke = chatModel.chat("Tell me a joke about Java");

        System.out.println(joke);
    }
}
