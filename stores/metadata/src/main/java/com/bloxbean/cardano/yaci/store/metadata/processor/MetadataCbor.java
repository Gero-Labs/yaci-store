package com.bloxbean.cardano.yaci.store.metadata.processor;

import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Encodes each label like Shelley serialiseTxMetadataToCbor (Map.singleton label value).
 * Metadatum maps are ordered pairs, including repeated keys; a Java/CBOR Map loses that
 * information. The original auxiliary bytes also avoid Yaci's earlier canonical sorting.
 */
final class MetadataCbor {
    private MetadataCbor() {}

    static Map<String, String> labels(String auxiliaryCbor, String legacyMetadataCbor) {
        boolean original = auxiliaryCbor != null && !auxiliaryCbor.isEmpty();
        String input = original ? auxiliaryCbor : legacyMetadataCbor;
        if (input == null || input.isEmpty()) return Map.of();
        Reader reader = new Reader(HexUtil.decodeHexString(input));
        Map<String, String> labels = original ? reader.auxiliary() : reader.metadata();
        reader.requireEnd();
        return labels;
    }

    private record Head(int major, BigInteger argument) {
        boolean indefinite() { return argument == null; }
    }

    private record Value(byte[] scalar, int major, List<Value> children) {
        void encode(ByteArrayOutputStream output) {
            Deque<Value> remaining = new ArrayDeque<>();
            remaining.push(this);
            while (!remaining.isEmpty()) {
                Value next = remaining.pop();
                if (next.scalar != null) {
                    output.writeBytes(next.scalar);
                } else {
                    output.writeBytes(header(next.major, BigInteger.valueOf(
                            next.major == 5 ? next.children.size() / 2 : next.children.size())));
                    for (int i = next.children.size() - 1; i >= 0; i--)
                        remaining.push(next.children.get(i));
                }
            }
        }
    }

    private static final class Frame {
        final int major;
        final int expected;
        final List<Value> children = new ArrayList<>();

        Frame(int major, int expected) {
            this.major = major;
            this.expected = expected;
        }
    }

    private static final class Reader {
        private final byte[] input;
        private int offset;

        Reader(byte[] input) { this.input = input; }

        Map<String, String> auxiliary() {
            int major = peek() >>> 5;
            if (major == 6) {
                Head tag = head();
                if (!BigInteger.valueOf(259).equals(tag.argument)) throw invalid();
                Head wrapper = expect(5);
                Map<String, String> labels = Map.of();
                int pairs = 0;
                boolean seenMetadata = false;
                while (more(wrapper, pairs)) {
                    Head key = expect(0);
                    if (BigInteger.ZERO.equals(key.argument)) {
                        if (seenMetadata) throw invalid();
                        labels = metadata();
                        seenMetadata = true;
                    } else {
                        skip();
                    }
                    pairs++;
                }
                return labels;
            }
            if (major == 4) {
                Head wrapper = expect(4);
                if (!wrapper.indefinite() && !BigInteger.TWO.equals(wrapper.argument)) throw invalid();
                Map<String, String> labels = metadata();
                if ((peek() >>> 5) != 4) throw invalid();
                skip(); // Allegra/Mary native scripts.
                if (wrapper.indefinite() && read() != 0xff) throw invalid();
                return labels;
            }
            return metadata(); // Shelley: auxiliary data is the metadata map itself.
        }

        Map<String, String> metadata() {
            Head map = expect(5);
            Map<String, String> result = new LinkedHashMap<>();
            int pairs = 0;
            while (more(map, pairs)) {
                Head label = expect(0);
                Value value = value();
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                output.write(0xa1);
                output.writeBytes(header(0, label.argument));
                value.encode(output);
                result.put(label.argument.toString(), HexUtil.encodeHexString(output.toByteArray()));
                pairs++;
            }
            return result;
        }

        // Iterative traversal also preserves the existing deep-list ingestion behavior.
        private Value value() {
            Deque<Frame> frames = new ArrayDeque<>();
            Value completed = null;
            while (true) {
                if (completed == null) {
                    Head item = head();
                    if (item.major == 4 || item.major == 5) {
                        int count = item.indefinite() ? -1 : length(item.argument);
                        if (item.major == 5 && count >= 0) count = Math.multiplyExact(count, 2);
                        frames.push(new Frame(item.major, count));
                    } else {
                        if (item.major > 3) throw invalid(); // Ledger metadatum has no tags/specials.
                        ByteArrayOutputStream scalar = new ByteArrayOutputStream();
                        if (item.major < 2) {
                            if (item.indefinite()) throw invalid();
                            scalar.writeBytes(header(item.major, item.argument));
                        } else {
                            byte[] bytes = string(item);
                            scalar.writeBytes(header(item.major, BigInteger.valueOf(bytes.length)));
                            scalar.writeBytes(bytes);
                        }
                        completed = new Value(scalar.toByteArray(), -1, null);
                    }
                }
                if (completed != null) {
                    if (frames.isEmpty()) return completed;
                    frames.peek().children.add(completed);
                    completed = null;
                }
                while (!frames.isEmpty()) {
                    Frame frame = frames.peek();
                    boolean done = frame.expected >= 0 ? frame.children.size() == frame.expected : peek() == 0xff;
                    if (!done) break;
                    if (frame.expected < 0) read();
                    if (frame.major == 5 && (frame.children.size() & 1) != 0) throw invalid();
                    completed = new Value(null, frame.major, frames.pop().children);
                    if (frames.isEmpty()) return completed;
                    frames.peek().children.add(completed);
                    completed = null;
                }
            }
        }

        private byte[] string(Head item) {
            if (!item.indefinite()) return bytes(length(item.argument));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            while (peek() != 0xff) {
                Head chunk = expect(item.major);
                if (chunk.indefinite()) throw invalid();
                output.writeBytes(bytes(length(chunk.argument)));
            }
            read();
            return output.toByteArray();
        }

        // Auxiliary script fields are skipped, never interpreted as metadata or re-encoded.
        private void skip() {
            Deque<Integer> counts = new ArrayDeque<>();
            counts.push(1);
            while (!counts.isEmpty()) {
                int remaining = counts.pop();
                if (remaining == 0) continue;
                if (remaining == -1 && peek() == 0xff) { read(); continue; }
                counts.push(remaining == -1 ? -1 : remaining - 1);
                Head item = head();
                switch (item.major) {
                    case 2, 3 -> string(item);
                    case 4, 5 -> {
                        int count = item.indefinite() ? -1 : length(item.argument);
                        counts.push(item.major == 5 && count >= 0 ? Math.multiplyExact(count, 2) : count);
                    }
                    case 6 -> {
                        if (item.indefinite()) throw invalid();
                        counts.push(1);
                    }
                    default -> { if (item.indefinite()) throw invalid(); }
                }
            }
        }

        private boolean more(Head container, int count) {
            if (!container.indefinite()) return BigInteger.valueOf(count).compareTo(container.argument) < 0;
            if (peek() != 0xff) return true;
            read();
            return false;
        }

        private Head expect(int major) {
            Head item = head();
            if (item.major != major || (major == 0 && item.indefinite())) throw invalid();
            return item;
        }

        private Head head() {
            int first = read();
            int additional = first & 31;
            if (additional < 24) return new Head(first >>> 5, BigInteger.valueOf(additional));
            if (additional == 31) return new Head(first >>> 5, null);
            if (additional > 27) throw invalid();
            return new Head(first >>> 5, new BigInteger(1, bytes(1 << (additional - 24))));
        }

        private int length(BigInteger value) {
            int size = value.intValueExact();
            if (size < 0 || size > input.length - offset) throw invalid();
            return size;
        }

        private byte[] bytes(int size) {
            if (size > input.length - offset) throw invalid();
            byte[] bytes = java.util.Arrays.copyOfRange(input, offset, offset + size);
            offset += size;
            return bytes;
        }

        private int peek() { if (offset == input.length) throw invalid(); return input[offset] & 255; }
        private int read() { int value = peek(); offset++; return value; }
        private void requireEnd() { if (offset != input.length) throw invalid(); }
    }

    private static byte[] header(int major, BigInteger value) {
        if (value.compareTo(BigInteger.valueOf(24)) < 0) return new byte[]{(byte) ((major << 5) | value.intValue())};
        int size = value.bitLength() <= 8 ? 1 : value.bitLength() <= 16 ? 2 : value.bitLength() <= 32 ? 4 : 8;
        byte[] result = new byte[size + 1];
        result[0] = (byte) ((major << 5) | (size == 1 ? 24 : size == 2 ? 25 : size == 4 ? 26 : 27));
        for (int i = size; i > 0; i--) {
            result[i] = value.byteValue();
            value = value.shiftRight(8);
        }
        return result;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid transaction metadata auxiliary CBOR");
    }
}
