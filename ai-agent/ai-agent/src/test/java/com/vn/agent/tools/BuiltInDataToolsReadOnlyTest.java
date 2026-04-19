package com.vn.agent.tools;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.springframework.ai.tool.annotation.Tool;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-16 — TOOL-08 read-only enforcement at build time via an ASM 9.7 bytecode scan of
 * {@code BuiltInDataTools.class}. Replaces what an ArchUnit rule (dropped in Phase 2
 * per D-10) would otherwise enforce. Two independent assertions per {@code @Tool}
 * method body:
 *
 * <ol>
 *   <li><b>No mutation paths.</b> FAIL if any {@code INVOKEVIRTUAL} /
 *       {@code INVOKEINTERFACE} targets {@code io/jmix/core/DataManager.save} /
 *       {@code saveContext} / {@code remove}, or any method on
 *       {@code jakarta/persistence/EntityManager}.</li>
 *   <li><b>No LLM-authored JPQL.</b> FAIL if a {@code @Tool} method contains BOTH
 *       (a) a call to {@code createQuery}/{@code createNativeQuery} or the
 *       {@code LoadContext.Query} constructor AND (b) a
 *       {@code makeConcatWithConstants}/{@code StringBuilder.append} whose operand
 *       stack reached it via an {@code ALOAD}/{@code ILOAD} of a method-parameter
 *       local slot. The existing {@code countRecords} concat uses
 *       {@code mc.getName()} (return of a call on a LOCAL, not a parameter) —
 *       allowed. If a future change passes an LLM parameter straight into a query
 *       string, this test fails before merge.</li>
 * </ol>
 *
 * <p>This is the <b>conservative v1 variant</b> documented in the plan (Task 4):
 * it inspects the concat's preceding instructions within the same method rather
 * than running a full ASM {@code Analyzer + SourceInterpreter} dataflow. It is
 * slightly over-restrictive (matches the spirit of D-16) and precise enough for a
 * single-class scan.</p>
 */
class BuiltInDataToolsReadOnlyTest {

    private static final String DATA_MANAGER_OWNER = "io/jmix/core/DataManager";
    private static final Set<String> FORBIDDEN_DATA_MANAGER_METHODS =
            Set.of("save", "saveContext", "remove");
    private static final String ENTITY_MANAGER_OWNER = "jakarta/persistence/EntityManager";
    private static final Set<String> QUERY_CREATION_METHODS =
            Set.of("createQuery", "createNativeQuery");
    private static final String LOAD_CONTEXT_QUERY_OWNER = "io/jmix/core/LoadContext$Query";

    @Test
    void noMutationPathsInToolBodies() throws Exception {
        Set<String> toolMethods = discoverToolAnnotatedMethodNames();
        assertThat(toolMethods)
                .as("@Tool-annotated methods must exist on BuiltInDataTools")
                .isNotEmpty();

        List<String> violations = new ArrayList<>();

        ClassReader cr = new ClassReader(readBytes(BuiltInDataTools.class));
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!toolMethods.contains(name)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String n,
                                                String desc, boolean itf) {
                        if (DATA_MANAGER_OWNER.equals(owner)
                                && FORBIDDEN_DATA_MANAGER_METHODS.contains(n)) {
                            violations.add("BuiltInDataTools." + name
                                    + " calls forbidden DataManager." + n);
                        }
                        if (ENTITY_MANAGER_OWNER.equals(owner)) {
                            violations.add("BuiltInDataTools." + name
                                    + " calls EntityManager." + n
                                    + " (JPA persistence context is forbidden per CLAUDE.md + TOOL-08)");
                        }
                    }
                };
            }
        }, 0);

        assertThat(violations)
                .as("TOOL-08 read-only enforcement — forbidden instructions found")
                .isEmpty();
    }

    @Test
    void noJpqlStringBuiltFromMethodParameters() throws Exception {
        Map<String, MethodScanResult> perMethod = new LinkedHashMap<>();
        Set<String> toolMethods = discoverToolAnnotatedMethodNames();
        // Map method name -> first parameter local slot (1 if instance method without wide types).
        // Parameters of @Tool methods on BuiltInDataTools are String / FilterNode / Integer —
        // all reference types, each occupies exactly one local slot. Parameter slots start at 1
        // (slot 0 = `this`) and run to the end of the parameter list.
        Map<String, Integer> paramSlotEnd = computeParamSlotEnd();

        ClassReader cr = new ClassReader(readBytes(BuiltInDataTools.class));
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!toolMethods.contains(name)) {
                    return null;
                }
                MethodScanResult result = new MethodScanResult();
                perMethod.put(name, result);
                Integer lastParamSlot = paramSlotEnd.get(name);
                return new MethodVisitor(Opcodes.ASM9) {
                    // Track the local slot most recently ALOADed; a StringBuilder.append
                    // or makeConcatWithConstants immediately consuming a parameter slot is
                    // the conservative-v1 red flag (plan 03-04 Task 4).
                    int lastLoadedLocalSlot = -1;

                    @Override
                    public void visitVarInsn(int opcode, int var) {
                        if (opcode == Opcodes.ALOAD || opcode == Opcodes.ILOAD
                                || opcode == Opcodes.LLOAD || opcode == Opcodes.DLOAD
                                || opcode == Opcodes.FLOAD) {
                            lastLoadedLocalSlot = var;
                            if (lastParamSlot != null && var >= 1 && var <= lastParamSlot) {
                                result.paramLoadedRecently = true;
                            } else {
                                // Any non-param local load resets the "param just loaded" flag.
                                result.paramLoadedRecently = false;
                            }
                        } else {
                            result.paramLoadedRecently = false;
                        }
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String n,
                                                String desc, boolean itf) {
                        if (LOAD_CONTEXT_QUERY_OWNER.equals(owner) && "<init>".equals(n)) {
                            result.queryBuildersCalled = true;
                        }
                        if (QUERY_CREATION_METHODS.contains(n)) {
                            result.queryBuildersCalled = true;
                        }
                        if ("java/lang/StringBuilder".equals(owner) && "append".equals(n)
                                && result.paramLoadedRecently) {
                            result.paramFlowedIntoConcat = true;
                        }
                        result.paramLoadedRecently = false; // each call consumes the stack
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String n, String descriptor,
                                                       Handle bootstrapMethodHandle,
                                                       Object... bootstrapMethodArguments) {
                        if ("makeConcatWithConstants".equals(n)
                                && result.paramLoadedRecently) {
                            result.paramFlowedIntoConcat = true;
                        }
                        result.paramLoadedRecently = false;
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        result.paramLoadedRecently = false;
                    }
                };
            }
        }, 0);

        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, MethodScanResult> e : perMethod.entrySet()) {
            MethodScanResult r = e.getValue();
            if (r.queryBuildersCalled && r.paramFlowedIntoConcat) {
                violations.add("BuiltInDataTools." + e.getKey()
                        + " concatenates a method parameter into a query string "
                        + "(forbidden per TOOL-08 / D-16 — parameterize with Condition instead)");
            }
        }

        assertThat(violations)
                .as("TOOL-08 / D-16 — no LLM-parameter-derived JPQL permitted")
                .isEmpty();
    }

    // ---- helpers ----

    private static byte[] readBytes(Class<?> c) throws Exception {
        String resource = c.getName().replace('.', '/') + ".class";
        try (InputStream in = c.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("classfile not on classpath: " + resource);
            }
            return in.readAllBytes();
        }
    }

    private static Set<String> discoverToolAnnotatedMethodNames() {
        Set<String> out = new LinkedHashSet<>();
        for (Method m : BuiltInDataTools.class.getDeclaredMethods()) {
            if (m.isAnnotationPresent(Tool.class)) {
                out.add(m.getName());
            }
        }
        return out;
    }

    /**
     * For each {@code @Tool} method, compute the highest local-variable slot that
     * corresponds to a method parameter (inclusive). Uses {@link Type#getArgumentTypes(String)}
     * summing each argument's JVM slot size (category-2 types consume 2 slots).
     */
    private static Map<String, Integer> computeParamSlotEnd() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Method m : BuiltInDataTools.class.getDeclaredMethods()) {
            if (!m.isAnnotationPresent(Tool.class)) {
                continue;
            }
            Type[] args = Type.getArgumentTypes(Type.getMethodDescriptor(m));
            int slot = 0; // post-this cursor
            for (Type t : args) {
                slot += t.getSize();
            }
            // Params occupy slots [1, 1 + slot - 1] = [1, slot]
            out.put(m.getName(), slot);
        }
        return out;
    }

    /** Per-method mutable scan state. */
    private static final class MethodScanResult {
        boolean queryBuildersCalled;
        boolean paramFlowedIntoConcat;
        boolean paramLoadedRecently;
    }
}
