package com.ailevels.level5;

import com.ailevels.common.dto.TraceStep;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;

import java.util.ArrayList;
import java.util.List;

/**
 * Вспомогательный класс: запускает «агента» — LLM-вызов с конкретным system-промптом,
 * набором инструментов и мини-ReAct циклом.
 *
 * Каждый «агент» в уровне 5 — это просто разная конфигурация одного и того же LLM.
 * Разделение на агентов — концептуальное, не техническое.
 */
public class AgentRunner {

    private final ChatLanguageModel llm;
    private final MultiAgentTools tools;
    private static final int MAX_ITER = 5;

    public AgentRunner(ChatLanguageModel llm, MultiAgentTools tools) {
        this.llm = llm;
        this.tools = tools;
    }

    /**
     * Запускает агента: system-промпт + задача + набор инструментов.
     * Добавляет шаги в общий trace с префиксом агента.
     */
    public String run(String agentName, String systemPrompt, String task,
                      List<ToolSpecification> specs, List<TraceStep> trace) {

        trace.add(TraceStep.info("🤖 " + agentName + " получил задачу", task));

        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(SystemMessage.from(systemPrompt));
        msgs.add(UserMessage.from(task));

        for (int i = 0; i < MAX_ITER; i++) {
            Response<AiMessage> resp = (specs == null || specs.isEmpty())
                    ? llm.generate(msgs)
                    : llm.generate(msgs, specs);

            AiMessage ai = resp.content();
            msgs.add(ai);

            if (!ai.hasToolExecutionRequests()) {
                String result = ai.text();
                trace.add(TraceStep.model("🤖 " + agentName + " → ответ", result));
                return result;
            }

            for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                trace.add(TraceStep.toolCall(
                        "🤖 " + agentName + " → вызов «" + req.name() + "»", req.arguments()));
                String r = tools.dispatch(req.name(), req.arguments());
                trace.add(TraceStep.toolResult("  Результат «" + req.name() + "»", r));
                msgs.add(ToolExecutionResultMessage.from(req, r));
            }
        }

        String fallback = "(агент " + agentName + " достиг лимита итераций)";
        trace.add(TraceStep.info("⚠️ " + agentName, fallback));
        return fallback;
    }
}
