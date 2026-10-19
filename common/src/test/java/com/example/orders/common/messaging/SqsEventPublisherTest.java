package com.example.orders.common.messaging;

import com.example.orders.common.event.OrderEvent;
import com.example.orders.common.event.OrderEventCodec;
import io.awspring.cloud.sqs.operations.SendResult;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqsEventPublisherTest {

    private final SqsTemplate template = mock(SqsTemplate.class);
    private final SqsEventPublisher publisher = new SqsEventPublisher(template, new OrderEventCodec());

    @Test
    @SuppressWarnings("unchecked")
    void rawBodiesAreSentInBatchesOfAtMostTen() {
        when(template.<String>sendMany(eq("q"), anyCollection())).thenReturn(new SendResult.Batch<>(Set.of(), Set.of()));
        List<String> bodies = IntStream.range(0, 23).mapToObj(i -> "body-" + i).toList();

        publisher.publishRaw("q", bodies);

        ArgumentCaptor<Collection<Message<String>>> batches = ArgumentCaptor.forClass(Collection.class);
        verify(template, times(3)).sendMany(eq("q"), batches.capture());
        assertThat(batches.getAllValues()).extracting(Collection::size).containsExactly(10, 10, 3);
        assertThat(batches.getAllValues().get(2)).extracting(Message::getPayload)
                .containsExactly("body-20", "body-21", "body-22");
    }

    @Test
    @SuppressWarnings("unchecked")
    void partialBatchFailureIsAnError() {
        SendResult.Failed<String> failed = mock(SendResult.Failed.class);
        when(template.<String>sendMany(eq("q"), anyCollection())).thenReturn(new SendResult.Batch<>(Set.of(), Set.of(failed)));

        assertThatThrownBy(() -> publisher.publishRaw("q", List.of("a", "b")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1 of 2");
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishSendsTheEncodedEvent() {
        OrderEvent event = OrderEvent.inventoryRelease(UUID.randomUUID(), "payment declined", Instant.now());

        publisher.publish("inventory-queue", event);

        verify(template).send(any(Consumer.class));
    }
}
