package com.diagnostic.agent.agent;

import com.diagnostic.agent.memory.ChatMemoryStore;
import com.diagnostic.agent.memory.MessageType;
import com.diagnostic.agent.memory.StoredMessage;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PromptContextBuilder {

    private static final int MAX_HISTORY_MESSAGES = 10;

    private final ChatMemoryStore memoryStore;

    public PromptContextBuilder(ChatMemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    public String buildContext(String sessionId) {
        List<StoredMessage> fullHistory = memoryStore.get(sessionId);
        if (fullHistory.isEmpty()) {
            return "";
        }

        List<StoredMessage> window = fullHistory.stream()
                .skip(Math.max(0, fullHistory.size() - MAX_HISTORY_MESSAGES))
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("=== 历史会话 ===\n");
        for (StoredMessage msg : window) {
            String role = msg.type() == MessageType.USER ? "用户" : "助手";
            sb.append(role).append(": ").append(msg.text()).append("\n");
        }
        return sb.toString();
    }
}
