/*
 * Copyright (c) 2015, 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.graal.replacements;

import static com.oracle.graal.nodes.graphbuilderconf.IntrinsicContext.CompilationContext.INLINE_AFTER_PARSING;

import java.util.HashMap;
import java.util.Map;

import com.oracle.graal.bytecode.BytecodeProvider;
import com.oracle.graal.debug.Debug;
import com.oracle.graal.java.GraphBuilderPhase;
import com.oracle.graal.nodes.EncodedGraph;
import com.oracle.graal.nodes.GraphEncoder;
import com.oracle.graal.nodes.StructuredGraph;
import com.oracle.graal.nodes.StructuredGraph.AllowAssumptions;
import com.oracle.graal.nodes.graphbuilderconf.GraphBuilderConfiguration;
import com.oracle.graal.nodes.graphbuilderconf.IntrinsicContext;
import com.oracle.graal.phases.OptimisticOptimizations;
import com.oracle.graal.phases.common.CanonicalizerPhase;
import com.oracle.graal.phases.tiers.PhaseContext;
import com.oracle.graal.phases.util.Providers;

import jdk.vm.ci.code.Architecture;
import jdk.vm.ci.meta.ResolvedJavaMethod;

/**
 * A graph decoder that provides all necessary encoded graphs on-the-fly (by parsing the methods and
 * encoding the graphs).
 */
public class CachingPEGraphDecoder extends PEGraphDecoder {

    protected final Providers providers;
    protected final GraphBuilderConfiguration graphBuilderConfig;
    protected final OptimisticOptimizations optimisticOpts;
    private final AllowAssumptions allowAssumptions;
    private final Map<ResolvedJavaMethod, EncodedGraph> graphCache;

    public CachingPEGraphDecoder(Providers providers, GraphBuilderConfiguration graphBuilderConfig, OptimisticOptimizations optimisticOpts, AllowAssumptions allowAssumptions,
                    Architecture architecture) {
        super(providers.getMetaAccess(), providers.getConstantReflection(), providers.getConstantFieldProvider(), providers.getStampProvider(), architecture);

        this.providers = providers;
        this.graphBuilderConfig = graphBuilderConfig;
        this.optimisticOpts = optimisticOpts;
        this.allowAssumptions = allowAssumptions;
        this.graphCache = new HashMap<>();
    }

    protected GraphBuilderPhase.Instance createGraphBuilderPhaseInstance(IntrinsicContext initialIntrinsicContext) {
        return new GraphBuilderPhase.Instance(providers.getMetaAccess(), providers.getStampProvider(), providers.getConstantReflection(), providers.getConstantFieldProvider(), graphBuilderConfig,
                        optimisticOpts, initialIntrinsicContext);
    }

    @SuppressWarnings("try")
    private EncodedGraph createGraph(ResolvedJavaMethod method, BytecodeProvider intrinsicBytecodeProvider) {
        StructuredGraph graph = new StructuredGraph(method, allowAssumptions);
        try (Debug.Scope scope = Debug.scope("createGraph", graph)) {
            IntrinsicContext initialIntrinsicContext = intrinsicBytecodeProvider != null ? new IntrinsicContext(method, method, intrinsicBytecodeProvider, INLINE_AFTER_PARSING) : null;
            GraphBuilderPhase.Instance graphBuilderPhaseInstance = createGraphBuilderPhaseInstance(initialIntrinsicContext);
            graphBuilderPhaseInstance.apply(graph);

            PhaseContext context = new PhaseContext(providers);
            new CanonicalizerPhase().apply(graph, context);

            EncodedGraph encodedGraph = GraphEncoder.encodeSingleGraph(graph, architecture);
            graphCache.put(method, encodedGraph);
            return encodedGraph;

        } catch (Throwable ex) {
            throw Debug.handle(ex);
        }
    }

    @Override
    protected EncodedGraph lookupEncodedGraph(ResolvedJavaMethod method, BytecodeProvider intrinsicBytecodeProvider) {
        EncodedGraph result = graphCache.get(method);
        if (result == null && method.hasBytecodes()) {
            result = createGraph(method, intrinsicBytecodeProvider);
        }
        return result;
    }
}
