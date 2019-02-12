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
package org.graalvm.tools.lsp.interop;

import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.oracle.truffle.api.interop.ForeignAccess;
import com.oracle.truffle.api.interop.Message;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.nodes.Node;

public final class ObjectStructures {

    public static Map<Object, Object> asMap(TruffleObject object, MessageNodes nodes) {
        TruffleObject keys;
        try {
            keys = ForeignAccess.sendKeys(nodes.keys, object, true);
            boolean hasSize = ForeignAccess.sendHasSize(nodes.hasSize, keys);
            if (!hasSize) {
                return null;
            }
        } catch (UnsupportedMessageException ex) {
            return null;
        }
        return new ObjectMap(object, keys, nodes);
    }

    public static List<Object> asList(TruffleObject object, MessageNodes nodes) {
        if (!ForeignAccess.sendHasSize(nodes.hasSize, object)) {
            return null;
        }
        return new ObjectList(nodes, object);
    }

    private static class ObjectMap extends AbstractMap<Object, Object> {

        private final TruffleObject object;
        private final TruffleObject keys;
        private final MessageNodes nodes;

        ObjectMap(TruffleObject object, TruffleObject keys, MessageNodes nodes) {
            this.object = object;
            this.keys = keys;
            this.nodes = nodes;
        }

        @Override
        public Set<Entry<Object, Object>> entrySet() {
            try {
                Number size = (Number) ForeignAccess.sendGetSize(nodes.getSize, keys);
                return new LazyEntries(keys, size.intValue());
            } catch (UnsupportedMessageException ex) {
                return Collections.emptySet();
            }
        }

        @Override
        public Object get(Object key) {
            try {
                return ForeignAccess.sendRead(nodes.read, object, key);
            } catch (UnknownIdentifierException ex) {
                return null;    // key not present in the map
            } catch (UnsupportedMessageException ex) {
                throw ex.raise();
            }
        }

        @Override
        public Object put(Object key, Object value) {
            Object prev = get(key);
            try {
                ForeignAccess.sendWrite(nodes.write, object, key, value);
            } catch (UnknownIdentifierException | UnsupportedTypeException | UnsupportedMessageException ex) {
                throw ex.raise();
            }
            return prev;
        }

        private final class LazyEntries extends AbstractSet<Entry<Object, Object>> {

            private final TruffleObject props;
            private final int size;

            LazyEntries(TruffleObject props, int size) {
                this.props = props;
                this.size = size;
            }

            @Override
            public Iterator<Entry<Object, Object>> iterator() {
                return new LazyIterator();
            }

            @Override
            public int size() {
                return size;
            }

            private final class LazyIterator implements Iterator<Entry<Object, Object>> {

                private int index;

                LazyIterator() {
                    index = 0;
                }

                @Override
                public boolean hasNext() {
                    return index < size;
                }

                @Override
                public Entry<Object, Object> next() {
                    Object key;
                    try {
                        key = ForeignAccess.sendRead(nodes.read, props, index++);
                    } catch (UnknownIdentifierException | UnsupportedMessageException ex) {
                        throw ex.raise();
                    }
                    return new TruffleEntry(key);
                }

                @Override
                public void remove() {
                    throw new UnsupportedOperationException("remove not supported.");
                }

            }
        }

        private final class TruffleEntry implements Entry<Object, Object> {
            private final Object key;

            TruffleEntry(Object key) {
                this.key = key;
            }

            @Override
            public Object getKey() {
                return key;
            }

            @Override
            public Object getValue() {
                try {
                    return ForeignAccess.sendRead(nodes.read, object, key);
                } catch (UnknownIdentifierException | UnsupportedMessageException ex) {
                    throw ex.raise();
                }
            }

            @Override
            public Object setValue(Object value) {
                Object prev = getValue();
                try {
                    ForeignAccess.sendWrite(nodes.write, object, key, value);
                } catch (UnknownIdentifierException | UnsupportedMessageException | UnsupportedTypeException ex) {
                    throw ex.raise();
                }
                return prev;
            }
        }
    }

    private static class ObjectList extends AbstractList<Object> {

        private final MessageNodes nodes;
        protected final TruffleObject object;

        ObjectList(MessageNodes nodes, TruffleObject object) {
            this.nodes = nodes;
            this.object = object;
        }

        @Override
        public Object get(int index) {
            try {
                return ForeignAccess.sendRead(nodes.read, object, index);
            } catch (UnknownIdentifierException | UnsupportedMessageException ex) {
                throw ex.raise();
            }
        }

        @Override
        public Object set(int index, Object element) {
            Object prev = get(index);
            try {
                ForeignAccess.sendWrite(nodes.write, object, index, element);
            } catch (UnknownIdentifierException | UnsupportedTypeException | UnsupportedMessageException ex) {
                throw ex.raise();
            }
            return prev;
        }

        @Override
        public int size() {
            try {
                Number size = (Number) ForeignAccess.sendGetSize(nodes.getSize, object);
                return size.intValue();
            } catch (UnsupportedMessageException ex) {
                return 0;
            }
        }

    }

    public static class MessageNodes {

        public final Node keyInfo;
        public final Node keys;
        public final Node hasSize;
        public final Node getSize;
        public final Node read;
        public final Node write;

        public MessageNodes() {
            keyInfo = Message.KEY_INFO.createNode();
            keys = Message.KEYS.createNode();
            hasSize = Message.HAS_SIZE.createNode();
            getSize = Message.GET_SIZE.createNode();
            read = Message.READ.createNode();
            write = Message.WRITE.createNode();
        }
    }
}
