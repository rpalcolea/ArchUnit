package com.tngtech.archunit.lang;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.Test;

import static com.google.common.collect.Iterables.getOnlyElement;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class SimpleConditionEventTest {
    @Test
    public void passes_corresponding_object_as_single_element_collection_with_message() {
        List<String> messages = new ArrayList<>();
        ConditionEvent.Handler handler = (correspondingObjects, message) ->
                messages.add(getOnlyElement(correspondingObjects) + ": " + message);

        SimpleConditionEvent.satisfied(77, "satisfied").handleWith(handler);
        assertThat(messages).containsExactly("77: satisfied");

        messages.clear();
        SimpleConditionEvent.violated(88, "violated").handleWith(handler);
        assertThat(messages).containsExactly("88: violated");
    }

    @Test
    public void passes_corresponding_object_as_single_element_collection_with_supplied_message() {
        List<String> messages = new ArrayList<>();
        ConditionEvent.Handler handler = (correspondingObjects, message) ->
                messages.add(getOnlyElement(correspondingObjects) + ": " + message);

        SimpleConditionEvent.satisfied(77, () -> "satisfied").handleWith(handler);
        assertThat(messages).containsExactly("77: satisfied");

        messages.clear();
        SimpleConditionEvent.violated(88, () -> "violated").handleWith(handler);
        assertThat(messages).containsExactly("88: violated");
    }

    @Test
    public void supplied_message_is_only_computed_when_requested() {
        AtomicInteger supplied = new AtomicInteger();
        ConditionEvent event = SimpleConditionEvent.violated("obj", () -> {
            supplied.incrementAndGet();
            return "message";
        });

        assertThat(event.isViolation()).isTrue();
        assertThat(event.invert().isViolation()).isFalse();
        assertThat(supplied).hasValue(0);

        assertThat(event.getDescriptionLines()).containsExactly("message");
        assertThat(supplied).hasValue(1);
    }

    @Test
    public void supplied_message_is_computed_only_once() {
        AtomicInteger supplied = new AtomicInteger();
        ConditionEvent event = SimpleConditionEvent.violated("obj", () -> {
            supplied.incrementAndGet();
            return "message";
        });

        event.getDescriptionLines();
        event.handleWith((objects, message) -> { });
        String ignored = event.toString();
        ConditionEvent inverted = event.invert();
        inverted.getDescriptionLines();

        assertThat(supplied).hasValue(1);
    }

    @Test
    public void events_with_supplied_and_with_constant_message_behave_the_same() {
        for (boolean satisfied : new boolean[]{true, false}) {
            ConditionEvent constant = new SimpleConditionEvent("obj", satisfied, "message");
            ConditionEvent supplied = new SimpleConditionEvent("obj", satisfied, () -> "message");

            assertSameBehavior(supplied, constant);
            assertSameBehavior(supplied.invert(), constant.invert());
        }
    }

    private void assertSameBehavior(ConditionEvent actual, ConditionEvent expected) {
        assertThat(actual.isViolation()).isEqualTo(expected.isViolation());
        assertThat(actual.getDescriptionLines()).isEqualTo(expected.getDescriptionLines());
        assertThat(actual.toString()).isEqualTo(expected.toString());

        List<Object> actualHandled = new ArrayList<>();
        actual.handleWith((objects, message) -> actualHandled.add(objects + message));
        List<Object> expectedHandled = new ArrayList<>();
        expected.handleWith((objects, message) -> expectedHandled.add(objects + message));
        assertThat(actualHandled).isEqualTo(expectedHandled);
    }

    @Test
    public void satisfied_event_with_null_constant_message_preserves_existing_behavior() {
        ConditionEvent event = SimpleConditionEvent.satisfied("obj", (String) null);

        assertThat(event.isViolation()).isFalse();
        assertThat(event.getDescriptionLines()).containsExactly((String) null);
        List<String> messages = new ArrayList<>();
        event.handleWith((objects, message) -> messages.add(message));
        assertThat(messages).containsExactly((String) null);
        assertThat(event.toString()).contains("message=null");
        assertThatThrownBy(event::invert).isInstanceOf(NullPointerException.class);
    }

    @Test
    public void each_message_consumer_can_trigger_lazy_rendering() {
        List<Consumer<ConditionEvent>> consumers = Arrays.asList(
                event -> assertThat(event.getDescriptionLines()).containsExactly("message"),
                event -> event.handleWith((objects, message) -> {
                    assertThat(objects.toArray()).containsExactly("obj");
                    assertThat(message).isEqualTo("message");
                }),
                event -> assertThat(event.toString()).contains("message=message"));

        for (Consumer<ConditionEvent> consumer : consumers) {
            AtomicInteger supplied = new AtomicInteger();
            ConditionEvent event = SimpleConditionEvent.violated("obj", () -> {
                supplied.incrementAndGet();
                return "message";
            });
            assertThat(supplied).hasValue(0);
            consumer.accept(event);
            assertThat(supplied).hasValue(1);
            consumer.accept(event);
            assertThat(supplied).hasValue(1);
        }
    }

    @Test
    public void rejects_empty_message_of_violation() {
        assertThatThrownBy(() -> SimpleConditionEvent.violated("obj", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Message may not be empty for violation");
        assertThatThrownBy(() -> SimpleConditionEvent.satisfied("obj", " ").invert())
                .isInstanceOf(IllegalArgumentException.class);

        ConditionEvent suppliedViolation = SimpleConditionEvent.violated("obj", () -> " ");
        assertThatThrownBy(suppliedViolation::getDescriptionLines).isInstanceOf(IllegalArgumentException.class);
        ConditionEvent invertedSupplied = SimpleConditionEvent.satisfied("obj", () -> " ").invert();
        assertThatThrownBy(invertedSupplied::getDescriptionLines).isInstanceOf(IllegalArgumentException.class);

        assertThat(SimpleConditionEvent.satisfied("obj", () -> " ").getDescriptionLines()).containsExactly(" ");
    }
}
