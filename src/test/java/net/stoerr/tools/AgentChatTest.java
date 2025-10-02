package net.stoerr.tools;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Test for OpenAI chat functionality using langchain4j's agentic framework
 */
@Disabled
public class AgentChatTest {

    /**
     * Simple calculator tool for the agent to use
     */
    public static class Calculator {

        @Tool("Add two numbers")
        public double add(double a, double b) {
            return a + b;
        }

        @Tool("Multiply two numbers")
        public double multiply(double a, double b) {
            return a * b;
        }

        @Tool("Get the current weather for a city")
        public String getWeather(String city) {
            // Mock weather service
            return "The weather in " + city + " is sunny with 22°C";
        }
    }

    /**
     * AI Agent interface that can use tools
     */
    interface MathAgent {
        String chat(String message);
    }

    @Test
    public void testAgentWithTools() {
        String apiKey = System.getenv("OPENAI_API_KEY");

        ChatModel chatModel = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName("gpt-4")
                .build();

        Calculator calculator = new Calculator();

        MathAgent agent = AiServices.builder(MathAgent.class)
                .chatModel(chatModel)
                .tools(calculator)
                .build();

        String response = agent.chat("What is 15 multiplied by 7, and then add 23 to the result?");

        System.out.println("Agent response: " + response);
    }

    @Test
    public void testAgentWithWeatherTool() {
        String apiKey = System.getenv("OPENAI_API_KEY");

        ChatModel chatModel = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName("gpt-4")
                .build();

        Calculator calculator = new Calculator();

        MathAgent agent = AiServices.builder(MathAgent.class)
                .chatModel(chatModel)
                .tools(calculator)
                .build();

        String response = agent.chat("What's the weather like in Berlin?");

        System.out.println("Weather response: " + response);
    }

    /**
     * More complex agent with system message
     */
    interface MathTutor {
        @SystemMessage("You are a helpful math tutor. Always explain your calculations step by step.")
        String teach(String mathProblem);
    }

    @Test
    public void testMathTutorAgent() {
        String apiKey = System.getenv("OPENAI_API_KEY");

        ChatModel chatModel = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName("gpt-4")
                .build();

        Calculator calculator = new Calculator();

        MathTutor tutor = AiServices.builder(MathTutor.class)
                .chatModel(chatModel)
                .tools(calculator)
                .build();

        String response = tutor.teach("How do I calculate the area of a circle with radius 5?");

        System.out.println("Tutor response: " + response);
    }
}
