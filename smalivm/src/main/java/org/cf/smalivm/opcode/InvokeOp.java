package org.cf.smalivm.opcode;

import org.cf.smalivm.MethodReflector;
import org.cf.smalivm.ObjectInstantiator;
import org.cf.smalivm.SideEffect;
import org.cf.smalivm.UnhandledVirtualException;
import org.cf.smalivm.VirtualException;
import org.cf.smalivm.VirtualMachine;
import org.cf.smalivm.VirtualMachineException;
import org.cf.smalivm.context.ExecutionContext;
import org.cf.smalivm.context.ExecutionGraph;
import org.cf.smalivm.context.ExecutionNode;
import org.cf.smalivm.context.HeapItem;
import org.cf.smalivm.context.MethodState;
import org.cf.smalivm.dex.CommonTypes;
import org.cf.smalivm.emulate.MethodEmulator;
import org.cf.smalivm.type.ClassManager;
import org.cf.smalivm.type.UninitializedInstance;
import org.cf.smalivm.type.UnknownValue;
import org.cf.smalivm.type.VirtualGeneric;
import org.cf.smalivm.type.VirtualMethod;
import org.cf.util.ClassNameUtils;
import org.cf.util.Utils;
import org.jf.dexlib2.builder.MethodLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.util.List;

import javax.annotation.Nullable;

public class InvokeOp extends ExecutionContextOp {

    private static final Logger log = LoggerFactory.getLogger(InvokeOp.class.getSimpleName());

    private final VirtualMethod method;
    private final int[] parameterRegisters;
    private final String[] analyzedParameterTypes;
    private final VirtualMachine vm;
    private final ClassManager classManager;
    private SideEffect.Level sideEffectLevel;

    InvokeOp(MethodLocation location, MethodLocation child, VirtualMethod method, int[] parameterRegisters,
             VirtualMachine vm) {
        super(location, child);

        this.method = method;
        this.parameterRegisters = parameterRegisters;
        analyzedParameterTypes = new String[method.getParameterTypeNames().size()];
        this.vm = vm;
        classManager = vm.getClassManager();
        sideEffectLevel = SideEffect.Level.STRONG;
    }

    @Override
    public void execute(ExecutionNode node, ExecutionContext context) {
        // TODO: In order to get working call stacks, refactor this to delegate most of the work to MethodExecutor.
        // This will remove InvokeOp as a weirdly complex op, and probably allow some methods to be made protected.
        // It also keeps things clear with method execution delegated to the class with the same name.
        // MethodExecutor can maintain a mapping such that calleeContext -> (callerContext, caller address)
        // With this mapping, stack traces can be reconstructed.

        MethodState callerMethodState = context.getMethodState();
        if (method.getSignature().equals(CommonTypes.OBJECT + "-><init>()V")) {
            // Object.<init> is a special little snow flake
            try {
                executeLocalObjectInit(callerMethodState);
            } catch (ClassNotFoundException | InstantiationException | IllegalAccessException |
                                             IllegalArgumentException | InvocationTargetException e) {
                log.error("Unexpected real exception initializing Object", e);
            }
            return;
        }

        analyzeParameterTypes(callerMethodState);

        // Have to do this at run time because robust type flow analysis is harder than just examining at run time
        VirtualMethod targetMethod = method;
        if (getName().startsWith("invoke-virtual") && !method.isFinal()) {
            int targetRegister = parameterRegisters[0];
            HeapItem item = context.getMethodState().peekRegister(targetRegister);
            targetMethod = resolveTargetMethod(item.getValue());
        }
        // Shouldn't reference method member now. Should use targetMethod for everything.
        String targetSignature = targetMethod.getSignature();

        // Try to reflect or emulate before executing local method.
        if (vm.getConfiguration().isSafe(targetSignature) || MethodEmulator.canEmulate(targetSignature)) {
            ExecutionContext calleeContext = buildNonLocalCalleeContext(context);
            boolean allArgumentsKnown = allArgumentsKnown(calleeContext.getMethodState());
            if (allArgumentsKnown || MethodEmulator.canHandleUnknownValues(targetSignature)) {
                executeNonLocalMethod(targetSignature, callerMethodState, calleeContext, node);
                return;
            } else {
                if (log.isTraceEnabled()) {
                    log.trace("Not emulating / reflecting {} because all args not known.", targetSignature);
                }
                assumeMaximumUnknown(callerMethodState);
                return;
            }
        }

        if (classManager.isFrameworkClass(targetSignature) && !classManager.isSafeFrameworkClass(targetSignature)) {
            if (log.isDebugEnabled()) {
                log.debug("Not executing unsafe framework method: {}. Assuming maximum ambiguity.", targetSignature);
            }
            assumeMaximumUnknown(callerMethodState);
            return;
        }

        if (!targetMethod.hasImplementation()) {
            if (log.isWarnEnabled()) {
                if (!targetMethod.isNative()) {
                    // This can happen if a method returns an object which implements an interface
                    // but the object is unknown, so the real virtual of the invocation target
                    // can't be determined. That's why this is a warning and not an error.
                    log.warn("Attempting to execute local native method without implementation: {}. Assuming maximum " +
                             "" + "" + "ambiguity.", targetSignature);
                } else {
                    log.warn("Cannot execute local native method: {}. Assuming maximum ambiguity.", targetSignature);
                }
            }
            assumeMaximumUnknown(callerMethodState);
            return;
        }

        ExecutionContext calleeContext = buildLocalCalleeContext(context, targetMethod);
        executeLocalMethod(targetSignature, context, calleeContext);
    }

    public int[] getParameterRegisters() {
        return parameterRegisters;
    }

    public String getReturnType() {
        return method.getReturnType();
    }

    @Override
    public SideEffect.Level getSideEffectLevel() {
        return sideEffectLevel;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(getName());
        sb.append(" {");
        if (getName().contains("/range")) {
            sb.append("r").append(parameterRegisters[0]).append(" .. r")
                    .append(parameterRegisters[parameterRegisters.length - 1]);
        } else {
            if (parameterRegisters.length > 0) {
                for (int register : parameterRegisters) {
                    sb.append('r').append(register).append(", ");
                }
                sb.setLength(sb.length() - 2);
            }
        }
        sb.append("}, ").append(method);

        return sb.toString();
    }

    private boolean allArgumentsKnown(MethodState mState) {
        for (int parameterRegister = mState.getParameterStart(); parameterRegister < mState.getRegisterCount(); ) {
            HeapItem item = mState.peekParameter(parameterRegister);
            if (item.isUnknown()) {
                return false;
            }
            String type = item.getType();
            parameterRegister += Utils.getRegisterSize(type);
        }

        return true;
    }

    private void analyzeParameterTypes(MethodState callerState) {
        /*
         * Type can be confused here. For example, creating a short, int, boolean, or *null* all appear:
         * const/4 v0,0x0 (could be true, (int)0, or (short)0, null, etc.)
         * If a more restrictive virtual is given in the method signature, prefer that, for example:
         * method argument is an int but signature declares it as boolean, so switch it to boolean
         * However, if the virtual is less specific, such as a super class or interface, do not use the less specific
         * virtual. For example:
         * method argument is Lchild_class; but signature says Lparent_class;, prefer Lchild_class;
         */
        List<String> parameterTypes = method.getParameterTypeNames();
        for (int i = 0; i < parameterRegisters.length; i++) {
            int callerRegister = parameterRegisters[i];
            HeapItem item = callerState.readRegister(callerRegister);
            String parameterType = parameterTypes.get(i);
            String baseTypeName = item.getComponentBase();
            String type;
            if (item.isPrimitive() || ClassNameUtils.isPrimitive(baseTypeName)) {
                type = parameterType;
            } else {
                //Set<String> ancestorNames = vm.getAncestorEnumerator().enumerate(baseType);
                VirtualGeneric baseType = vm.getClassManager().getVirtualType(baseTypeName);
                if (baseType.getAncestors().contains(parameterType)) {
                    type = item.getType();
                } else {
                    type = parameterType;
                }
            }
            analyzedParameterTypes[i] = type;
        }
    }

    private void assignCalleeMethodArguments(MethodState callerState, MethodState calleeState) {
        int parameterRegister = calleeState.getParameterStart();
        for (int i = 0; i < parameterRegisters.length; i++) {
            int callerRegister = parameterRegisters[i];
            HeapItem item = callerState.readRegister(callerRegister);
            String parameterType = analyzedParameterTypes[i];
            Object value = item.getValue();
            if (item.isPrimitive() && !item.isUnknown()) {
                boolean hasNullByteValue =
                        item.getType().equals("I") && value instanceof Number && item.asInteger() == 0;
                if (hasNullByteValue && ClassNameUtils.isObject(parameterType)) {
                    value = null;
                } else {
                    // An I virtual may actually be a S, B, C, etc. Pass the cast virtual to simplify things.
                    value = Utils.castToPrimitive(value, parameterType);
                }
            }
            HeapItem parameterItem = new HeapItem(value, parameterType);
            calleeState.assignParameter(parameterRegister, parameterItem);
            parameterRegister += Utils.getRegisterSize(parameterType);
        }
    }

    private void assumeMaximumUnknown(MethodState callerMethodState) {
        // TODO: add option to mark all class states unknown instead of just method state
        for (int i = 0; i < method.getParameterTypeNames().size(); i++) {
            int register = parameterRegisters[i];
            HeapItem item = callerMethodState.readRegister(register);
            Object value = item.getValue();
            if (null == value) {
                // Nulls don't mutate.
                continue;
            }

            String type = analyzedParameterTypes[i];
            boolean isInitializing = method.getSignature().contains(";-><init>(");
            if (!isInitializing) {
                // May be immutable virtual, but if this is the initializer, internal state would be changing.
                if (vm.getConfiguration().isImmutable(type)) {
                    if (log.isTraceEnabled()) {
                        log.trace("{} (parameter) is immutable", type);
                    }
                    continue;
                }

                if (item.isImmutable()) {
                    // Parameter virtual might be "Ljava/lang/Object;" but actual virtual is "Ljava/lang/String";
                    if (log.isTraceEnabled()) {
                        log.trace("{} (actual) is immutable", type);
                    }
                    continue;
                }
            }

            item = HeapItem.newUnknown(type);
            if (log.isDebugEnabled()) {
                log.debug("{} is mutable and passed into unresolvable method execution, making Unknown", type);
            }

            callerMethodState.pokeRegister(register, item);
        }

        if (!method.returnsVoid()) {
            HeapItem item = HeapItem.newUnknown(method.getReturnType());
            callerMethodState.assignResultRegister(item);
        }
    }

    private ExecutionContext buildLocalCalleeContext(ExecutionContext callerContext, VirtualMethod localMethod) {
        ExecutionContext calleeContext = vm.spawnRootContext(localMethod, callerContext, getAddress());
        MethodState callerMethodState = callerContext.getMethodState();
        MethodState calleeMethodState = calleeContext.getMethodState();
        assignCalleeMethodArguments(callerMethodState, calleeMethodState);

        // VirtualClass state merging is handled by the VM.

        return calleeContext;
    }

    private ExecutionContext buildNonLocalCalleeContext(ExecutionContext callerContext) {
        ExecutionContext calleeContext = new ExecutionContext(vm, method);
        int parameterSize = method.getParameterSize();
        int registerCount = parameterSize;
        MethodState calleeMethodState =
                new MethodState(calleeContext, registerCount, method.getParameterTypeNames().size(), parameterSize);
        assignCalleeMethodArguments(callerContext.getMethodState(), calleeMethodState);
        calleeContext.setMethodState(calleeMethodState);
        calleeContext.registerCaller(callerContext, getAddress());

        return calleeContext;
    }

    private void executeLocalMethod(String methodSignature, ExecutionContext callerContext,
                                    ExecutionContext calleeContext) {
        ExecutionGraph graph = null;
        try {
            graph = vm.execute(methodSignature, calleeContext, callerContext, parameterRegisters);
        } catch (VirtualMachineException e) {
            log.warn(e.toString());
            if (e instanceof UnhandledVirtualException) {
                // TODO: handle this properly by bubbling up the exception
            }
        }

        if (graph == null) {
            // Maybe node visits or call depth exceeded?
            log.info("Problem executing {}, propagating ambiguity.", methodSignature);
            assumeMaximumUnknown(callerContext.getMethodState());

            return;
        }
        
        if (!method.getReturnType().equals(CommonTypes.VOID)) {
            HeapItem consensus = graph.getTerminatingRegisterConsensus(MethodState.ReturnRegister);
            callerContext.getMethodState().assignResultRegister(consensus);
        } else {
            if (methodSignature.contains(";-><init>(")) {
                // This was a call to a local parent <init> method
                int calleeInstanceRegister = calleeContext.getMethodState().getParameterStart();
                HeapItem newInstance = graph.getTerminatingRegisterConsensus(calleeInstanceRegister);
                int instanceRegister = parameterRegisters[0];
                callerContext.getMethodState().assignRegisterAndUpdateIdentities(instanceRegister, newInstance);
            }
        }

        sideEffectLevel = graph.getHighestSideEffectLevel();
    }

    private void executeLocalObjectInit(MethodState callerMethodState) throws ClassNotFoundException,
                                                                                      InstantiationException,
                                                                                      IllegalAccessException,
                                                                                      IllegalArgumentException,
                                                                                      InvocationTargetException {
        int instanceRegister = parameterRegisters[0];
        HeapItem instanceItem = callerMethodState.peekRegister(instanceRegister);
        UninitializedInstance uninitializedInstance = (UninitializedInstance) instanceItem.getValue();
        VirtualGeneric instanceType = uninitializedInstance.getType();

        // Create a Java class of the true type
        Class<?> klazz = vm.getClassLoader().loadClass(instanceType.getBinaryName());
        Object newInstance = ObjectInstantiator.newInstance(klazz);
        HeapItem newInstanceItem = new HeapItem(newInstance, instanceType.getName());
        callerMethodState.assignRegisterAndUpdateIdentities(instanceRegister, newInstanceItem);
    }

    private void executeNonLocalMethod(String methodDescriptor, MethodState callerMethodState,
                                       ExecutionContext calleeContext, ExecutionNode node) {
        if (MethodEmulator.canEmulate(methodDescriptor)) {
            MethodEmulator emulator = new MethodEmulator(vm, calleeContext, methodDescriptor);
            emulator.emulate();
            sideEffectLevel = emulator.getSideEffectLevel();
            if (emulator.getExceptions().size() > 0) {
                node.clearChildren();
                node.setExceptions(emulator.getExceptions());
                return;
            }
        } else if (vm.getConfiguration().isSafe(methodDescriptor)) {
            assert allArgumentsKnown(calleeContext.getMethodState());

            MethodReflector reflector = new MethodReflector(vm, method);
            try {
                reflector.reflect(calleeContext.getMethodState()); // playa play
            } catch (Exception e) {
                VirtualException exception = new VirtualException(e);
                node.setException(exception);
                node.clearChildren();
                return;
            }

            // Only safe, non-side-effect methods are allowed to be reflected.
            sideEffectLevel = SideEffect.Level.NONE;
        }

        if (!method.isStatic()) {
            // This is virtual and the instance parse may have been initialized or mutated.
            HeapItem originalInstanceItem = callerMethodState.peekRegister(parameterRegisters[0]);
            HeapItem newInstanceItem = calleeContext.getMethodState().peekParameter(0);
            if (originalInstanceItem.getValue() != newInstanceItem.getValue()) {
                // Instance has been initialized, i.e. was UninitializedInstance
                // Use assignRegisterAndUpdateIdentities because multiple registers may have an identical
                // UninitializedInstance, and those need to be updated with the new instance.
                callerMethodState.assignRegisterAndUpdateIdentities(parameterRegisters[0], newInstanceItem);
            } else {
                boolean isMutable = !vm.getConfiguration().isImmutable(newInstanceItem.getType());
                if (isMutable) {
                    // The instance virtual is mutable so could have changed. Record that it was changed for the
                    // optimizer.
                    callerMethodState.assignRegister(parameterRegisters[0], newInstanceItem);
                }
            }
        }

        if (!method.returnsVoid()) {
            HeapItem returnItem = calleeContext.getMethodState().readReturnRegister();
            callerMethodState.assignResultRegister(returnItem);
        }
    }

    private
    @Nullable
    VirtualMethod resolveTargetMethod(Object virtualReference) {
        /*
         * A method may not be defined in the class referenced by invoke op. The method implementation may be part
         * of the super class. This method searches ancestor hierarchy for the class which implements the method.
         */
        VirtualGeneric referenceType;
        if (virtualReference == null || virtualReference instanceof UnknownValue) {
            return method;
        }

        if (virtualReference instanceof UninitializedInstance) {
            UninitializedInstance instance = (UninitializedInstance) virtualReference;
            referenceType = instance.getType();
        } else {
            String targetType = ClassNameUtils.toInternal(virtualReference.getClass());
            referenceType = classManager.getVirtualType(targetType);
        }

        VirtualMethod targetMethod = referenceType.getMethod(method.getDescriptor());
        if (targetMethod != null && targetMethod.hasImplementation()) {
            return targetMethod;
        }
        //        for (VirtualGeneric ancestor : referenceType.getAncestors()) {
        //            targetMethod = referenceType.getMethod(method.getDescriptor());
        //            if (targetMethod != null && targetMethod.hasImplementation()) {
        //                return targetMethod;
        //            }
        //        }

        return method;
    }

}
