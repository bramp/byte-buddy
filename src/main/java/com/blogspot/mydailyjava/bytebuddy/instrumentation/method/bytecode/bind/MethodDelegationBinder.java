package com.blogspot.mydailyjava.bytebuddy.instrumentation.method.bytecode.bind;

import com.blogspot.mydailyjava.bytebuddy.instrumentation.method.MethodDescription;
import com.blogspot.mydailyjava.bytebuddy.instrumentation.method.bytecode.assign.Assignment;
import com.blogspot.mydailyjava.bytebuddy.instrumentation.method.bytecode.assign.MethodInvocation;
import com.blogspot.mydailyjava.bytebuddy.instrumentation.type.TypeDescription;
import org.objectweb.asm.MethodVisitor;

import java.util.*;

public interface MethodDelegationBinder {

    public static interface MethodInvoker {

        static enum Simple implements MethodInvoker {
            INSTANCE;

            @Override
            public Assignment invoke(MethodDescription methodDescription) {
                return MethodInvocation.invoke(methodDescription);
            }
        }

        Assignment invoke(MethodDescription methodDescription);
    }

    static interface Binding extends Assignment {

        static class Builder {

            private static class Build implements Binding {

                private final MethodDescription target;
                private final Assignment methodInvocation;
                private final List<Assignment> parameterAssignments;
                private final Map<?, Integer> registeredTargetIndices;
                private final Assignment returnValueAssignment;

                private Build(MethodDescription target,
                              Assignment methodInvocation,
                              List<Assignment> parameterAssignments,
                              Map<?, Integer> registeredTargetIndices,
                              Assignment returnValueAssignment) {
                    this.target = target;
                    this.methodInvocation = methodInvocation;
                    this.parameterAssignments = new ArrayList<Assignment>(parameterAssignments);
                    this.registeredTargetIndices = new HashMap<Object, Integer>(registeredTargetIndices);
                    this.returnValueAssignment = returnValueAssignment;
                }

                @Override
                public boolean isValid() {
                    boolean result = returnValueAssignment.isValid() && methodInvocation.isValid();
                    Iterator<Assignment> assignment = parameterAssignments.iterator();
                    while (result && assignment.hasNext()) {
                        result = assignment.next().isValid();
                    }
                    return result;
                }

                @Override
                public Integer getTargetParameterIndex(Object identificationToken) {
                    return registeredTargetIndices.get(identificationToken);
                }

                @Override
                public MethodDescription getTarget() {
                    return target;
                }

                @Override
                public Size apply(MethodVisitor methodVisitor) {
                    Size size = new Size(0, 0);
                    for (Assignment assignment : parameterAssignments) {
                        size = size.aggregate(assignment.apply(methodVisitor));
                    }
                    size = size.aggregate(methodInvocation.apply(methodVisitor));
                    return size.aggregate(returnValueAssignment.apply(methodVisitor));
                }

                @Override
                public String toString() {
                    return "Binding to " + target + " (" + (isValid() ? "valid" : "invalid") + ')';
                }
            }

            private final MethodInvoker methodInvoker;
            private final MethodDescription target;
            private final List<Assignment> parameterAssignments;
            private final Map<Object, Integer> registeredTargetIndices;

            public Builder(MethodInvoker methodInvoker, MethodDescription target) {
                this.methodInvoker = methodInvoker;
                this.target = target;
                parameterAssignments = new ArrayList<Assignment>(target.getParameterTypes().size());
                registeredTargetIndices = new LinkedHashMap<Object, Integer>(target.getParameterTypes().size());
            }

            public boolean append(Assignment assignment, int targetParameterIndex, Object identificationToken) {
                parameterAssignments.add(assignment);
                return registeredTargetIndices.put(identificationToken, targetParameterIndex) == null;
            }

            public Binding build(Assignment returnValueAssignment) {
                return new Build(target, methodInvoker.invoke(target), parameterAssignments, registeredTargetIndices, returnValueAssignment);
            }
        }

        Integer getTargetParameterIndex(Object identificationToken);

        MethodDescription getTarget();
    }

    static interface AmbiguityResolver {

        static enum Resolution {

            UNKNOWN(true),
            LEFT(false),
            RIGHT(false),
            AMBIGUOUS(true);

            private final boolean unresolved;

            private Resolution(boolean unresolved) {
                this.unresolved = unresolved;
            }

            public boolean isUnresolved() {
                return unresolved;
            }

            public Resolution merge(Resolution other) {
                switch (this) {
                    case UNKNOWN:
                        return other;
                    case AMBIGUOUS:
                        return AMBIGUOUS;
                    case LEFT:
                    case RIGHT:
                        return other == this ? other : AMBIGUOUS;
                    default:
                        throw new AssertionError();
                }
            }
        }

        static class Chain implements AmbiguityResolver {

            private final Iterable<AmbiguityResolver> ambiguityResolvers;

            public Chain(Iterable<AmbiguityResolver> ambiguityResolvers) {
                this.ambiguityResolvers = ambiguityResolvers;
            }

            @Override
            public Resolution resolve(MethodDescription source,
                                      Binding left,
                                      Binding right) {
                Resolution resolution = Resolution.UNKNOWN;
                Iterator<AmbiguityResolver> iterator = ambiguityResolvers.iterator();
                while (resolution.isUnresolved() && iterator.hasNext()) {
                    resolution = iterator.next().resolve(source, left, right);
                }
                return resolution;
            }
        }

        Resolution resolve(MethodDescription source, Binding left, Binding right);
    }

    static class Processor {

        private static final int ONLY = 0;
        private static final int LEFT = 0;
        private static final int RIGHT = 1;

        private final MethodDelegationBinder methodDelegationBinder;
        private final AmbiguityResolver ambiguityResolver;

        public Processor(MethodDelegationBinder methodDelegationBinder,
                         AmbiguityResolver ambiguityResolver) {
            this.methodDelegationBinder = methodDelegationBinder;
            this.ambiguityResolver = ambiguityResolver;
        }

        public MethodDelegationBinder getMethodDelegationBinder() {
            return methodDelegationBinder;
        }

        public AmbiguityResolver getAmbiguityResolver() {
            return ambiguityResolver;
        }

        public Binding process(TypeDescription typeDescription,
                               MethodDescription source,
                               Iterable<? extends MethodDescription> targets) {
            List<Binding> possibleDelegations = bind(typeDescription, source, targets);
            if (possibleDelegations.size() == 0) {
                throw new IllegalArgumentException("No method can be bound to " + source);
            }
            return resolve(source, possibleDelegations);
        }

        private List<Binding> bind(TypeDescription typeDescription,
                                   MethodDescription source,
                                   Iterable<? extends MethodDescription> targets) {
            List<Binding> possibleDelegations = new LinkedList<Binding>();
            for (MethodDescription target : targets) {
                Binding binding = methodDelegationBinder.bind(typeDescription, source, target);
                if (binding.isValid()) {
                    possibleDelegations.add(binding);
                }
            }
            return possibleDelegations;
        }

        private Binding resolve(MethodDescription source,
                                List<Binding> targets) {
            switch (targets.size()) {
                case 1:
                    return targets.get(ONLY);
                case 2: {
                    Binding left = targets.get(LEFT);
                    Binding right = targets.get(RIGHT);
                    switch (ambiguityResolver.resolve(source, left, right)) {
                        case LEFT:
                            return left;
                        case RIGHT:
                            return right;
                        case AMBIGUOUS:
                        case UNKNOWN:
                            throw new IllegalArgumentException("Could not resolve ambiguous delegation to " + left + " or " + right);
                        default:
                            throw new AssertionError();
                    }
                }
                default: /* case 3+: */ {
                    Binding left = targets.get(LEFT);
                    Binding right = targets.get(RIGHT);
                    switch (ambiguityResolver.resolve(source, left, right)) {
                        case LEFT:
                            targets.remove(RIGHT);
                            return resolve(source, targets);
                        case RIGHT:
                            targets.remove(LEFT);
                            return resolve(source, targets);
                        case AMBIGUOUS:
                        case UNKNOWN:
                            targets.remove(RIGHT); // Remove right element first due to index alteration!
                            targets.remove(LEFT);
                            Binding subResult = resolve(source, targets);
                            switch (ambiguityResolver.resolve(source, left, subResult).merge(ambiguityResolver.resolve(source, right, subResult))) {
                                case RIGHT:
                                    return subResult;
                                case LEFT:
                                case AMBIGUOUS:
                                case UNKNOWN:
                                    throw new IllegalArgumentException("Could not resolve ambiguous delegation to either " + left + " or " + right);
                                default:
                                    throw new AssertionError();
                            }
                        default:
                            throw new AssertionError();
                    }
                }
            }
        }
    }

    Binding bind(TypeDescription proxyType, MethodDescription source, MethodDescription target);
}