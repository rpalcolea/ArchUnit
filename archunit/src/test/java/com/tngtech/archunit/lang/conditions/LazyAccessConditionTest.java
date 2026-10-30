package com.tngtech.archunit.lang.conditions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvent;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static com.tngtech.archunit.base.DescribedPredicate.alwaysFalse;
import static com.tngtech.archunit.base.DescribedPredicate.alwaysTrue;
import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.core.domain.TestUtils.importClasses;
import static com.tngtech.archunit.lang.conditions.ArchConditions.beAccessedByMethodsThat;
import static com.tngtech.archunit.lang.conditions.ArchConditions.containAnyElementThat;
import static com.tngtech.archunit.lang.conditions.ArchConditions.containOnlyElementsThat;
import static com.tngtech.archunit.lang.conditions.ArchConditions.never;
import static java.util.Collections.emptySet;
import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;

class LazyAccessConditionTest {
    @ParameterizedTest
    @EnumSource(Combination.class)
    void access_events_preserve_messages_and_handled_objects(Combination combination) {
        Set<JavaAccess<?>> accesses = fixture().get(Caller.class).getAccessesFromSelf().stream()
                .filter(access -> access.getTargetOwner().isEquivalentTo(Target.class))
                .collect(toSet());
        assertThat(accesses).hasSize(4);

        List<DescribedPredicate<JavaAccess<?>>> predicates = new ArrayList<>();
        predicates.add(alwaysTrue());
        predicates.add(alwaysFalse());
        predicates.add(describe("access field", access -> access.getName().equals("field")));
        for (DescribedPredicate<JavaAccess<?>> predicate : predicates) {
            ArchCondition<JavaAccess<?>> lazy = combination.apply(new JavaAccessCondition<>(predicate));
            ArchCondition<JavaAccess<?>> eager = combination.apply(eagerAccessCondition(predicate));
            for (JavaAccess<?> access : accesses) {
                assertEquivalent(lazy, eager, access);
            }
            assertEquivalent(containAnyElementThat(lazy), containAnyElementThat(eager), accesses);
            assertEquivalent(containOnlyElementsThat(lazy), containOnlyElementsThat(eager), accesses);
            assertEquivalent(containAnyElementThat(lazy), containAnyElementThat(eager), emptySet());
            assertEquivalent(containOnlyElementsThat(lazy), containOnlyElementsThat(eager), emptySet());
        }
    }

    @ParameterizedTest
    @EnumSource(Combination.class)
    void field_access_events_preserve_messages_and_handled_objects(Combination combination) {
        Set<JavaFieldAccess> accesses = fixture().get(Caller.class).getFieldAccessesFromSelf().stream()
                .filter(access -> access.getTargetOwner().isEquivalentTo(Target.class))
                .collect(toSet());
        assertThat(accesses).hasSize(2);

        for (boolean satisfied : new boolean[]{true, false}) {
            DescribedPredicate<JavaFieldAccess> predicate = satisfied ? alwaysTrue() : alwaysFalse();
            for (JavaFieldAccess access : accesses) {
                assertEquivalent(combination.apply(new FieldAccessCondition(predicate)),
                        combination.apply(eagerAccessCondition(predicate)), access);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Combination.class)
    void incoming_field_access_events_preserve_messages_and_handled_objects(Combination combination) {
        JavaField field = fixture().get(Target.class).getField("field");
        assertThat(field.getAccessesToSelf()).hasSize(2);
        for (boolean satisfied : new boolean[]{true, false}) {
            DescribedPredicate<JavaMethod> predicate = satisfied ? alwaysTrue() : alwaysFalse();
            ArchCondition<JavaField> eager = new ArchCondition<JavaField>("be accessed by methods") {
                @Override
                public void check(JavaField item, ConditionEvents events) {
                    for (JavaFieldAccess access : item.getAccessesToSelf()) {
                        if (access.getOrigin() instanceof JavaMethod) {
                            events.add(new SimpleConditionEvent(item,
                                    predicate.test((JavaMethod) access.getOrigin()), access.getDescription()));
                        }
                    }
                }
            };
            assertEquivalent(combination.apply(beAccessedByMethodsThat(predicate)), combination.apply(eager), field);
        }
    }

    private static <T extends JavaAccess<?>> ArchCondition<T> eagerAccessCondition(DescribedPredicate<? super T> predicate) {
        return new ArchCondition<T>("access target where " + predicate.getDescription()) {
            @Override
            public void check(T item, ConditionEvents events) {
                events.add(new SimpleConditionEvent(item, predicate.test(item), item.getDescription()));
            }
        };
    }

    private static <T> void assertEquivalent(ArchCondition<T> lazy, ArchCondition<T> eager, T item) {
        ViolatedAndSatisfiedConditionEvents actual = new ViolatedAndSatisfiedConditionEvents();
        ViolatedAndSatisfiedConditionEvents expected = new ViolatedAndSatisfiedConditionEvents();
        lazy.check(item, actual);
        eager.check(item, expected);
        assertThat(actual.containViolation()).isEqualTo(expected.containViolation());
        assertThat(snapshot(actual.getAllowed())).isEqualTo(snapshot(expected.getAllowed()));
        assertThat(snapshot(actual.getViolating())).isEqualTo(snapshot(expected.getViolating()));
    }

    private static List<Object> snapshot(Collection<ConditionEvent> events) {
        List<Object> result = new ArrayList<>();
        for (ConditionEvent event : events) {
            result.add(event.isViolation());
            result.add(event.getDescriptionLines());
            event.handleWith((objects, message) -> {
                result.add(new HashSet<>(objects));
                result.add(message);
            });
            result.add(event.invert().getDescriptionLines());
            result.add(event.invert().invert().getDescriptionLines());
        }
        return result;
    }

    private static JavaClasses fixture() {
        return importClasses(Caller.class, Target.class);
    }

    enum Combination {
        SINGLE, NEGATED, DOUBLE_NEGATED, AND, AND_NOT, OR, OR_NOT, NEGATED_AND, NEGATED_AND_NOT, NEGATED_OR, NEGATED_OR_NOT;

        <T> ArchCondition<T> apply(ArchCondition<T> condition) {
            switch (this) {
                case SINGLE:
                    return condition;
                case NEGATED:
                    return never(condition);
                case DOUBLE_NEGATED:
                    return never(never(condition));
                case AND:
                    return condition.and(condition);
                case AND_NOT:
                    return condition.and(never(condition));
                case OR:
                    return condition.or(condition);
                case OR_NOT:
                    return condition.or(never(condition));
                case NEGATED_AND:
                    return never(condition.and(condition));
                case NEGATED_AND_NOT:
                    return never(condition.and(never(condition)));
                case NEGATED_OR:
                    return never(condition.or(condition));
                case NEGATED_OR_NOT:
                    return never(condition.or(never(condition)));
                default:
                    throw new AssertionError(this);
            }
        }
    }

    private static class Target {
        int field;

        void method() {
        }
    }

    private static class Caller {
        void call() {
            Target target = new Target();
            int value = target.field;
            target.field = value;
            target.method();
        }
    }
}
