package net.bytebuddy.test.utility;

import org.hamcrest.CoreMatchers;
import org.hamcrest.Description;
import org.hamcrest.TypeSafeMatcher;

import java.lang.reflect.*;
import java.util.*;

import static net.bytebuddy.utility.ByteBuddyCommons.join;
import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;

public class ObjectPropertyAssertion<T> {

    private static final boolean DEFAULT_BOOLEAN = false, OTHER_BOOLEAN = true;

    private static final byte DEFAULT_BYTE = 1, OTHER_BYTE = 42;

    private static final char DEFAULT_CHAR = 1, OTHER_CHAR = 42;

    private static final short DEFAULT_SHORT = 1, OTHER_SHORT = 42;

    private static final int DEFAULT_INT = 1, OTHER_INT = 42;

    private static final long DEFAULT_LONG = 1, OTHER_LONG = 42;

    private static final float DEFAULT_FLOAT = 1, OTHER_FLOAT = 42;

    private static final double DEFAULT_DOUBLE = 1, OTHER_DOUBLE = 42;

    private static final String DEFAULT_STRING = "foo", OTHER_STRING = "bar";

    private final Class<T> type;

    private final ApplicableRefinement refinement;

    private final ApplicableGenerator generator;

    private final ApplicableCreator creator;

    private final boolean skipSynthetic;

    private final String optionalToStringRegex;

    private final boolean skipToString;

    private final Set<String> ignoredFields;

    private ObjectPropertyAssertion(Class<T> type,
                                    ApplicableGenerator generator,
                                    ApplicableRefinement refinement,
                                    ApplicableCreator creator,
                                    boolean skipSynthetic,
                                    boolean skipToString,
                                    String optionalToStringRegex,
                                    Set<String> ignoredFields) {
        this.type = type;
        this.generator = generator;
        this.refinement = refinement;
        this.creator = creator;
        this.skipSynthetic = skipSynthetic;
        this.skipToString = skipToString;
        this.optionalToStringRegex = optionalToStringRegex;
        this.ignoredFields = ignoredFields;
    }

    public static <S> ObjectPropertyAssertion<S> of(Class<S> type) {
        return new ObjectPropertyAssertion<S>(type,
                new ApplicableGenerator(),
                new ApplicableRefinement(),
                new ApplicableCreator(),
                false,
                false,
                null,
                new HashSet<String>());
    }

    public ObjectPropertyAssertion<T> refine(Refinement<?> refinement) {
        return new ObjectPropertyAssertion<T>(type,
                generator,
                this.refinement.with(refinement),
                creator,
                skipSynthetic,
                skipToString,
                optionalToStringRegex,
                ignoredFields);
    }

    public ObjectPropertyAssertion<T> generate(Generator<?> generator) {
        return new ObjectPropertyAssertion<T>(type,
                this.generator.with(generator),
                refinement,
                creator,
                skipSynthetic,
                skipToString,
                optionalToStringRegex,
                ignoredFields);
    }

    public ObjectPropertyAssertion<T> create(Creator<?> creator) {
        return new ObjectPropertyAssertion<T>(type,
                generator,
                refinement,
                this.creator.with(creator),
                skipSynthetic,
                skipToString,
                optionalToStringRegex,
                ignoredFields);
    }

    public ObjectPropertyAssertion<T> skipSynthetic() {
        return new ObjectPropertyAssertion<T>(type, generator, refinement, creator, true, skipToString, optionalToStringRegex, ignoredFields);
    }

    public ObjectPropertyAssertion<T> skipToString() {
        return new ObjectPropertyAssertion<T>(type, generator, refinement, creator, skipSynthetic, true, optionalToStringRegex, ignoredFields);
    }

    public ObjectPropertyAssertion<T> specificToString(String stringRegex) {
        return new ObjectPropertyAssertion<T>(type, generator, refinement, creator, skipSynthetic, skipToString, stringRegex, ignoredFields);
    }

    public ObjectPropertyAssertion<T> ignoreFields(String... field) {
        Set<String> ignoredFields = new HashSet<String>(this.ignoredFields);
        ignoredFields.addAll(Arrays.asList(field));
        return new ObjectPropertyAssertion<T>(type, generator, refinement, creator, skipSynthetic, skipToString, optionalToStringRegex, ignoredFields);
    }

    public void apply() throws IllegalAccessException, InvocationTargetException, InstantiationException {
        if (type.isEnum()) {
            for (T instance : type.getEnumConstants()) {
                assertThat(instance.toString(), is(type.getCanonicalName()
                        .substring(type.getPackage().getName().length() + 1) + "." + ((Enum<?>) instance).name()));
            }
            return;
        }
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (constructor.isSynthetic() && skipSynthetic) {
                continue;
            }
            constructor.setAccessible(true);
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            Object[] actualArguments = new Object[parameterTypes.length];
            Object[] otherArguments = new Object[parameterTypes.length];
            int index = 0;
            for (Class<?> parameterType : parameterTypes) {
                putInstance(parameterType, actualArguments, otherArguments, index++);
            }
            int testIndex = 0;
            @SuppressWarnings("unchecked")
            T instance = (T) constructor.newInstance(actualArguments);
            assertThat(instance, is(instance));
            assertThat(instance, not(is((Object) null)));
            assertThat(instance, not(is(new Object())));
            Object similarInstance = constructor.newInstance(actualArguments);
            assertThat(instance.hashCode(), is(similarInstance.hashCode()));
            assertThat(instance, is(similarInstance));
            if (skipToString) {
                assertThat(instance.toString(), notNullValue());
            } else if (optionalToStringRegex == null) {
                checkString(instance);
            } else {
                assertThat(instance.toString(), new RegexMatcher(optionalToStringRegex));
            }
            for (Object otherArgument : otherArguments) {
                Object[] compareArguments = new Object[actualArguments.length];
                int argumentIndex = 0;
                for (Object actualArgument : actualArguments) {
                    if (argumentIndex == testIndex) {
                        compareArguments[argumentIndex] = otherArgument;
                    } else {
                        compareArguments[argumentIndex] = actualArgument;
                    }
                    argumentIndex++;
                }
                Object unlikeInstance = constructor.newInstance(compareArguments);
                assertThat(instance.hashCode(), not(is(unlikeInstance)));
                assertThat(instance, not(is(unlikeInstance)));
                testIndex++;
            }
        }
    }

    private void checkString(T instance) {
        assertThat(instance.toString(), CoreMatchers.startsWith(type.getCanonicalName()
                .substring(type.getPackage().getName().length() + 1) + "{"));
        assertThat(instance.toString(), endsWith("}"));
        Class<?> currentType = type;
        do {
            for (Field field : type.getDeclaredFields()) {
                if (!field.isSynthetic() && !Modifier.isStatic(field.getModifiers()) && !ignoredFields.contains(field.getName())) {
                    assertThat(instance.toString(), containsString(field.getName()));
                }
            }
        } while ((currentType = currentType.getSuperclass()) != Object.class);
    }

    public void applyMutable() throws IllegalAccessException, InvocationTargetException, InstantiationException {
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (constructor.isSynthetic() && skipSynthetic) {
                continue;
            }
            constructor.setAccessible(true);
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            Object[] actualArguments = new Object[parameterTypes.length];
            Object[] otherArguments = new Object[parameterTypes.length];
            int index = 0;
            for (Class<?> parameterType : parameterTypes) {
                putInstance(parameterType, actualArguments, otherArguments, index++);
            }
            @SuppressWarnings("unchecked")
            T instance = (T) constructor.newInstance(actualArguments);
            checkString(instance);
            assertThat(instance, is(instance));
            assertThat(instance, not(is((Object) null)));
            assertThat(instance, not(is(new Object())));
            assertThat(instance, not(is(constructor.newInstance(otherArguments))));
        }
    }

    private void putInstance(Class<?> parameterType, Object actualArguments, Object otherArguments, int index) {
        Object actualArgument, otherArgument;
        if (parameterType == boolean.class) {
            actualArgument = DEFAULT_BOOLEAN;
            otherArgument = OTHER_BOOLEAN;
        } else if (parameterType == byte.class) {
            actualArgument = DEFAULT_BYTE;
            otherArgument = OTHER_BYTE;
        } else if (parameterType == char.class) {
            actualArgument = DEFAULT_CHAR;
            otherArgument = OTHER_CHAR;
        } else if (parameterType == short.class) {
            actualArgument = DEFAULT_SHORT;
            otherArgument = OTHER_SHORT;
        } else if (parameterType == int.class) {
            actualArgument = DEFAULT_INT;
            otherArgument = OTHER_INT;
        } else if (parameterType == long.class) {
            actualArgument = DEFAULT_LONG;
            otherArgument = OTHER_LONG;
        } else if (parameterType == float.class) {
            actualArgument = DEFAULT_FLOAT;
            otherArgument = OTHER_FLOAT;
        } else if (parameterType == double.class) {
            actualArgument = DEFAULT_DOUBLE;
            otherArgument = OTHER_DOUBLE;
        } else if (parameterType.isEnum()) {
            Object[] enumConstants = parameterType.getEnumConstants();
            if (enumConstants.length == 1) {
                throw new IllegalArgumentException("Enum with only one constant: " + parameterType);
            }
            actualArgument = enumConstants[0];
            otherArgument = enumConstants[1];
        } else if (parameterType.isArray()) {
            actualArgument = Array.newInstance(parameterType.getComponentType(), 1);
            otherArgument = Array.newInstance(parameterType.getComponentType(), 1);
            putInstance(parameterType.getComponentType(), actualArgument, otherArgument, 0);
        } else {
            actualArgument = creator.replace(parameterType, generator, false);
            refinement.apply(actualArgument);
            otherArgument = creator.replace(parameterType, generator, true);
            refinement.apply(otherArgument);
        }
        Array.set(actualArguments, index, actualArgument);
        Array.set(otherArguments, index, otherArgument);
    }

    public interface Refinement<T> {

        void apply(T mock);
    }

    public interface Generator<T> {

        Class<? extends T> generate();
    }

    public interface Creator<T> {

        T create();
    }

    private static class ApplicableRefinement {

        private final List<Refinement<?>> refinements;

        private ApplicableRefinement() {
            refinements = Collections.emptyList();
        }

        private ApplicableRefinement(List<Refinement<?>> refinements) {
            this.refinements = refinements;
        }

        @SuppressWarnings("unchecked")
        private void apply(Object mock) {
            for (Refinement refinement : refinements) {
                ParameterizedType generic = (ParameterizedType) refinement.getClass().getGenericInterfaces()[0];
                Class<?> restrained = generic.getActualTypeArguments()[0] instanceof ParameterizedType
                        ? (Class<?>) ((ParameterizedType) generic.getActualTypeArguments()[0]).getRawType()
                        : (Class<?>) generic.getActualTypeArguments()[0];
                if (restrained.isInstance(mock)) {
                    refinement.apply(mock);
                }
            }
        }

        private ApplicableRefinement with(Refinement<?> refinement) {
            return new ApplicableRefinement(join(refinements, refinement));
        }
    }

    private static class ApplicableGenerator {

        private final List<Generator<?>> generators;

        private ApplicableGenerator() {
            generators = Collections.emptyList();
        }

        private ApplicableGenerator(List<Generator<?>> generators) {
            this.generators = generators;
        }

        private Object generate(Class<?> type, boolean alternative) {
            for (Generator<?> generator : generators) {
                ParameterizedType generic = (ParameterizedType) generator.getClass().getGenericInterfaces()[0];
                Class<?> restrained = generic.getActualTypeArguments()[0] instanceof ParameterizedType
                        ? (Class<?>) ((ParameterizedType) generic.getActualTypeArguments()[0]).getRawType()
                        : (Class<?>) generic.getActualTypeArguments()[0];
                if (type.isAssignableFrom(restrained)) {
                    type = generator.generate();
                }
            }
            return type == String.class
                    ? alternative ? OTHER_STRING : DEFAULT_STRING
                    : mock(type);
        }

        private ApplicableGenerator with(Generator<?> generator) {
            return new ApplicableGenerator(join(generators, generator));
        }
    }

    private static class ApplicableCreator {

        private final List<Creator<?>> creators;

        private ApplicableCreator() {
            creators = Collections.emptyList();
        }

        private ApplicableCreator(List<Creator<?>> creators) {
            this.creators = creators;
        }

        private Object replace(Class<?> type, ApplicableGenerator generator, boolean alternative) {
            for (Creator<?> creator : creators) {
                ParameterizedType generic = (ParameterizedType) creator.getClass().getGenericInterfaces()[0];
                Class<?> restrained = generic.getActualTypeArguments()[0] instanceof ParameterizedType
                        ? (Class<?>) ((ParameterizedType) generic.getActualTypeArguments()[0]).getRawType()
                        : (Class<?>) generic.getActualTypeArguments()[0];
                if (type.isAssignableFrom(restrained)) {
                    return creator.create();
                }
            }
            return generator.generate(type, alternative);
        }

        private ApplicableCreator with(Creator<?> creator) {
            return new ApplicableCreator(join(creators, creator));
        }
    }

    private static class RegexMatcher extends TypeSafeMatcher<String> {

        private final String regex;

        public RegexMatcher(final String regex) {
            this.regex = regex;
        }

        @Override
        public void describeTo(final Description description) {
            description.appendText("matches regex='" + regex + "'");
        }

        @Override
        public boolean matchesSafely(final String string) {
            return string.matches(regex);
        }
    }
}
