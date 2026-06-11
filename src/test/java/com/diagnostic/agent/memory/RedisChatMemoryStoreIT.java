package com.diagnostic.agent.memory;

import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("redis-it")
@Testcontainers
class RedisChatMemoryStoreIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Container
    @ServiceConnection
    static RedisContainer redis = new RedisContainer("redis:7-alpine");

    @Autowired
    private ChatMemoryStore memoryStore;

    // ---- Scenario 1: add + get ----

    @Test
    void shouldStoreAndRetrieveMessages() {
        memoryStore.clear("s1");
        StoredMessage msg = StoredMessage.of(MessageType.USER, "hello");

        memoryStore.add("s1", msg);
        List<StoredMessage> messages = memoryStore.get("s1");

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).text()).isEqualTo("hello");
        assertThat(messages.get(0).type()).isEqualTo(MessageType.USER);
    }

    // ---- Scenario 2: addAll + get (multiple messages) ----

    @Test
    void shouldStoreAndRetrieveBatchMessages() {
        memoryStore.clear("s2");
        List<StoredMessage> batch = List.of(
                StoredMessage.of(MessageType.USER, "q1"),
                StoredMessage.of(MessageType.ASSISTANT, "a1"),
                StoredMessage.of(MessageType.USER, "q2")
        );

        memoryStore.addAll("s2", batch);
        List<StoredMessage> messages = memoryStore.get("s2");

        assertThat(messages).hasSize(3);
        assertThat(messages.get(0).text()).isEqualTo("q1");
        assertThat(messages.get(2).text()).isEqualTo("q2");
    }

    // ---- Scenario 3: clear ----

    @Test
    void shouldClearSessionMessages() {
        memoryStore.add("s3", StoredMessage.of(MessageType.USER, "hello"));
        assertThat(memoryStore.get("s3")).isNotEmpty();

        memoryStore.clear("s3");

        assertThat(memoryStore.get("s3")).isEmpty();
    }

    // ---- Scenario 4: empty session returns empty list ----

    @Test
    void shouldReturnEmptyListForUnknownSession() {
        List<StoredMessage> messages = memoryStore.get("nonexistent");

        assertThat(messages).isNotNull();
        assertThat(messages).isEmpty();
    }

    // ---- Scenario 5: session isolation ----

    @Test
    void shouldIsolateSessions() {
        memoryStore.clear("a");
        memoryStore.clear("b");

        memoryStore.add("a", StoredMessage.of(MessageType.USER, "msg-a"));
        memoryStore.add("b", StoredMessage.of(MessageType.USER, "msg-b"));

        List<StoredMessage> sessionA = memoryStore.get("a");
        List<StoredMessage> sessionB = memoryStore.get("b");

        assertThat(sessionA).hasSize(1);
        assertThat(sessionA.get(0).text()).isEqualTo("msg-a");
        assertThat(sessionB).hasSize(1);
        assertThat(sessionB.get(0).text()).isEqualTo("msg-b");
    }

    // ---- Scenario 6: get returns mutable copy (not internal reference) ----

    @Test
    void shouldReturnIndependentCopy() {
        memoryStore.clear("s6");
        memoryStore.add("s6", StoredMessage.of(MessageType.USER, "original"));

        List<StoredMessage> copy1 = memoryStore.get("s6");
        List<StoredMessage> copy2 = memoryStore.get("s6");

        assertThat(copy1).isNotSameAs(copy2);
        assertThat(copy1).hasSize(1);
        assertThat(copy2).hasSize(1);
    }
}
