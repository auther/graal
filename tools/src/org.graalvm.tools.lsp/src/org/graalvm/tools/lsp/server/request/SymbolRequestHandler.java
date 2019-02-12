/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
package org.graalvm.tools.lsp.server.request;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.graalvm.tools.lsp.api.ContextAwareExecutor;
import org.graalvm.tools.lsp.interop.ObjectStructures;
import org.graalvm.tools.lsp.interop.ObjectStructures.MessageNodes;
import org.graalvm.tools.lsp.server.utils.SourceUtils;
import org.graalvm.tools.lsp.server.utils.TextDocumentSurrogateMap;

import com.oracle.truffle.api.instrumentation.InstrumentableNode;
import com.oracle.truffle.api.instrumentation.LoadSourceSectionEvent;
import com.oracle.truffle.api.instrumentation.LoadSourceSectionListener;
import com.oracle.truffle.api.instrumentation.SourceSectionFilter;
import com.oracle.truffle.api.instrumentation.SourceSectionFilter.SourcePredicate;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.instrumentation.StandardTags.DeclarationTag;
import com.oracle.truffle.api.instrumentation.TruffleInstrument.Env;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.nodes.Node;

public final class SymbolRequestHandler extends AbstractRequestHandler {

    private final MessageNodes messageNodes;

    public SymbolRequestHandler(Env env, TextDocumentSurrogateMap surrogateMap, ContextAwareExecutor contextAwareExecutor, MessageNodes messageNodes) {
        super(env, surrogateMap, contextAwareExecutor);
        this.messageNodes = messageNodes;
    }

    public List<Either<SymbolInformation, DocumentSymbol>> documentSymbolWithEnteredContext(URI uri) {
        SourcePredicate srcPredicate = newDefaultSourcePredicateBuilder().uriOrTruffleName(uri).build();
        Collection<? extends SymbolInformation> symbols = symbolWithEnteredContext(srcPredicate);
        List<Either<SymbolInformation, DocumentSymbol>> symbolList = new ArrayList<>(symbols.size());
        for (SymbolInformation symbolInfo : symbols) {
            symbolList.add(Either.forLeft(symbolInfo));
        }
        return symbolList;
    }

    Collection<? extends SymbolInformation> symbolWithEnteredContext(SourcePredicate srcPredicate) {
        Set<SymbolInformation> symbolInformation = new LinkedHashSet<>();
        SourceSectionFilter filter = SourceSectionFilter.newBuilder().sourceIs(srcPredicate).tagIs(DeclarationTag.class).build();
        env.getInstrumenter().attachLoadSourceSectionListener(
                        filter,
                        new LoadSourceSectionListener() {

                            @Override
                            public void onLoad(LoadSourceSectionEvent event) {
                                Node node = event.getNode();
                                if (!(node instanceof InstrumentableNode)) {
                                    return;
                                }
                                InstrumentableNode instrumentableNode = (InstrumentableNode) node;
                                Object nodeObject = instrumentableNode.getNodeObject();
                                if (!(nodeObject instanceof TruffleObject)) {
                                    return;
                                }
                                Map<Object, Object> map = ObjectStructures.asMap((TruffleObject) nodeObject, messageNodes);
                                String name = map.get(DeclarationTag.NAME).toString();
                                SymbolKind kind = map.containsKey(DeclarationTag.KIND) ? declarationKindToSmybolKind(map.get(DeclarationTag.KIND)) : null;
                                Range range = SourceUtils.sourceSectionToRange(node.getSourceSection());
                                String container = map.containsKey(DeclarationTag.CONTAINER) ? map.get(DeclarationTag.CONTAINER).toString() : "";
                                URI fixedUri = SourceUtils.getOrFixFileUri(node.getSourceSection().getSource());
                                SymbolInformation si = new SymbolInformation(name, kind != null ? kind : SymbolKind.Null, new Location(fixedUri.toString(), range),
                                                container);
                                symbolInformation.add(si);
                            }

                            private SymbolKind declarationKindToSmybolKind(Object kindObj) {
                                if (kindObj == null) {
                                    return null;
                                }
                                String kind = kindObj.toString();
                                return Arrays.stream(SymbolKind.values()).filter(sk -> sk.name().toLowerCase().equals(kind.toLowerCase())).findFirst().orElse(null);
                            }
                        }, true).dispose();

        // Fallback: search for generic RootTags
        if (symbolInformation.isEmpty()) {
            env.getInstrumenter().attachLoadSourceSectionListener(
                            SourceSectionFilter.newBuilder().sourceIs(srcPredicate).tagIs(StandardTags.RootTag.class).build(),
                            new LoadSourceSectionListener() {

                                @Override
                                public void onLoad(LoadSourceSectionEvent event) {
                                    if (!event.getSourceSection().isAvailable()) {
                                        return;
                                    }

                                    Node node = event.getNode();
                                    SymbolKind kind = SymbolKind.Function;
                                    Range range = SourceUtils.sourceSectionToRange(node.getSourceSection());
                                    URI fixedUri = SourceUtils.getOrFixFileUri(node.getSourceSection().getSource());
                                    SymbolInformation si = new SymbolInformation(node.getRootNode().getName(), kind, new Location(fixedUri.toString(), range));
                                    symbolInformation.add(si);
                                }
                            }, true).dispose();
        }

        return symbolInformation;
    }

    public List<? extends SymbolInformation> workspaceSymbolWithEnteredContext(@SuppressWarnings("unused") String query) {
        SourcePredicate srcPredicate = newDefaultSourcePredicateBuilder().build();
        return new ArrayList<>(symbolWithEnteredContext(srcPredicate));
    }
}
