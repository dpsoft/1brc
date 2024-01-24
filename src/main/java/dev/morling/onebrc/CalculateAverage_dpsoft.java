/*
 *  Copyright 2023 The original authors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package dev.morling.onebrc;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.Phaser;

public class CalculateAverage_dpsoft {
    private static final String FILE = "./measurements.txt";
    private static final int MAX_ROWS = 1 << 15;
    private static final int ROWS_MASK = MAX_ROWS - 1;

    public static void main(String[] args) throws IOException {
        final var cpus = Runtime.getRuntime().availableProcessors();
        final var segments = getMemorySegments(cpus);
        final var tasks = new MeasurementExtractor[segments.size()];
        final var phaser = new Phaser(segments.size());

        for (int i = 0; i < segments.size(); i++) {
            final var task = new MeasurementExtractor(segments.get(i), phaser);
            tasks[i] = task;
        }

        phaser.awaitAdvance(phaser.getPhase());

        final var allMeasurements = Arrays.stream(tasks)
                .parallel()
                .map(MeasurementExtractor::getMeasurements)
                .reduce(MeasurementMap::merge)
                .orElseThrow();

        final Map<String, Measurement> sorted = new TreeMap<>();
        for (Measurement m : allMeasurements.measurements) {
            if (m != null) {
                sorted.put(new String(m.name, StandardCharsets.UTF_8), m);
            }
        }

        System.out.println(sorted);

        System.exit(0);
    }

    // Credits to @spullara
    private static List<FileSegment> getMemorySegments(int numberOfSegments) throws IOException {
        try (var fileChannel = FileChannel.open(Path.of(FILE), StandardOpenOption.READ)) {
            var memorySegment = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size(), Arena.global());
            long fileSize = memorySegment.byteSize();
            long segmentSize = fileSize / numberOfSegments;

            List<FileSegment> segments = new ArrayList<>(numberOfSegments);

            if (segmentSize < 1_000_000) {
                segments.add(new FileSegment(0, fileSize));
                return segments;
            }

            for (int i = 0; i < numberOfSegments; i++) {
                long segStart = i * segmentSize;
                long segEnd = (i == numberOfSegments - 1) ? fileSize : segStart + segmentSize;
                segStart = findSegment(i, 0, memorySegment, segStart, segEnd);
                segEnd = findSegment(i, numberOfSegments - 1, memorySegment, segEnd, fileSize);
                segments.add(new FileSegment(segStart, segEnd));
            }

            return segments;
        }
    }

    record FileSegment(long start, long end) {
    }

    private static long findSegment(int i, int skipSegment, MemorySegment memSeg, long location, long fileSize) {
        if (i != skipSegment) {
            long remaining = fileSize - location;
            int bufferSize = remaining < 64 ? (int) remaining : 64;
            MemorySegment slice = memSeg.asSlice(location, bufferSize);
            for (int offset = 0; offset < slice.byteSize(); offset++) {
                if (slice.get(ValueLayout.OfChar.JAVA_BYTE, offset) == '\n') {
                    return location + offset + 1;
                }
            }
        }
        return location;
    }

    static final class MeasurementExtractor implements Runnable {
        private final FileSegment segment;
        private final Phaser phaser;
        private final MeasurementMap measurements = new MeasurementMap();

        MeasurementExtractor(FileSegment memorySegment, Phaser phaser) {
            this.segment = memorySegment;
            this.phaser = phaser;
            (new Thread(this)).start();
        }

        @Override
        public void run() {
            long segmentEnd = segment.end();
            try (var fileChannel = FileChannel.open(Path.of(FILE), StandardOpenOption.READ)) {
                var mbb = fileChannel.map(FileChannel.MapMode.READ_ONLY, segment.start(), segmentEnd - segment.start());
                mbb.order(ByteOrder.nativeOrder());

                if (segment.start() > 0) {
                    skipToFirstLine(mbb);
                }

                while (mbb.remaining() > 0 && mbb.position() <= segmentEnd) {
                    int pos = mbb.position();
                    int nameHash = hashName(mbb);
                    var m = measurements.getOrCompute(nameHash, mbb, pos);
                    int temp = readTemperatureFromBuffer(mbb);

                    m.sample(temp);
                }
            }
            catch (IOException e) {
                throw new RuntimeException("Error reading file", e);
            }
            finally {
                phaser.arriveAndAwaitAdvance();
            }
        }

        // inspired by @lawrey and @shipilev
        private static int hashName(MappedByteBuffer mbb) {
            int hash = 0;
            int idx = mbb.position();
            outer: while (true) {
                int name = mbb.getInt();
                for (int c = 0; c < 4; c++) {
                    int b = (name >> (c << 3)) & 0xFF;
                    if (b == ';') {
                        idx += c + 1;
                        break outer;
                    }
                    hash ^= b * 82805;
                }
                idx += 4;
            }

            var rewind = mbb.position() - idx;
            mbb.position(mbb.position() - rewind);
            return hash;
        }

        private static int readTemperatureFromBuffer(MappedByteBuffer mbb) {
            int temp = 0;
            boolean negative = false;

            outer: while (mbb.remaining() > 0) {
                int b = mbb.get();
                switch (b) {
                    case '-':
                        negative = true;
                        break;
                    default:
                        temp = 10 * temp + (b - '0');
                        break;
                    case '.':
                        b = mbb.get();
                        temp = 10 * temp + (b - '0');
                    case '\r':
                        mbb.get();
                    case '\n':
                        break outer;
                }
            }
            if (negative)
                temp = -temp;
            return temp;
        }

        public MeasurementMap getMeasurements() {
            return measurements;
        }

        // Skips to the first line in the buffer, used for chunk processing.
        private static void skipToFirstLine(MappedByteBuffer mbb) {
            while ((mbb.get() & 0xFF) >= ' ') {
                // Skip bytes until reaching the start of a line.
            }
        }
    }

    // open addressing map with linear probing
    static class MeasurementMap {
        private final Measurement[] measurements = new Measurement[MAX_ROWS];

        public Measurement getOrCompute(int hash, MappedByteBuffer mbb, int position) {
            int index = hash & ROWS_MASK;
            var measurement = measurements[index];
            if (measurement != null && hash == measurement.nameHash && Measurement.equalsTo(measurement.name, mbb, position)) {
                return measurement;
            }
            else {
                return compute(hash, mbb, position);
            }
        }

        private Measurement compute(int hash, MappedByteBuffer mbb, int position) {
            var index = hash & ROWS_MASK;
            Measurement m;

            while (true) {
                m = measurements[index];
                if (m == null || (hash == m.nameHash && Measurement.equalsTo(m.name, mbb, position))) {
                    break;
                }
                index = (index + 1) & ROWS_MASK;
            }

            if (m == null) {
                int len = mbb.position() - position - 1;
                byte[] bytes = new byte[len];
                mbb.position(position);
                mbb.get(bytes, 0, len);
                mbb.get();
                measurements[index] = m = new Measurement(bytes, hash);
            }

            return m;
        }

        public MeasurementMap merge(MeasurementMap otherMap) {
            for (Measurement other : otherMap.measurements) {
                if (other != null) {
                    int index = other.nameHash & ROWS_MASK;
                    Measurement m = measurements[index];
                    if (m == null || Arrays.equals(m.name, other.name)) {
                        measurements[index] = (m == null) ? other : m.merge(other);
                    }
                    else {
                        measurements[(index + 1) & ROWS_MASK] = other;
                    }
                }
            }
            return this;
        }
    }

    static final class Measurement {
        public final int nameHash;
        public final byte[] name;
        long sum = 0;
        int count = 0;
        long min = Integer.MAX_VALUE;
        long max = Integer.MIN_VALUE;

        // Default constructor for Measurement.
        public Measurement(byte[] name, int nameHash) {
            this.name = name;
            this.nameHash = nameHash;
        }

        public static boolean equalsTo(byte[] name, MappedByteBuffer mbb, int position) {
            int len = mbb.position() - position - 1;
            if (len != name.length)
                return false;
            for (int i = 0; i < len; i++) {
                if (name[i] != mbb.get(position + i))
                    return false;
            }
            return true;
        }

        public void sample(int temp) {
            min = Math.min(min, temp);
            max = Math.max(max, temp);
            sum += temp;
            count++;
        }

        public Measurement merge(Measurement m2) {
            if (m2 == null) {
                return this;
            }
            min = Math.min(min, m2.min);
            max = Math.max(max, m2.max);
            sum += m2.sum;
            count += m2.count;
            return this;
        }

        public String toString() {
            var min = String.format("%.1f", (double) this.min / 10.0);
            var avg = String.format("%.1f", ((double) this.sum / (double) this.count) / 10.0);
            var max = String.format("%.1f", (double) this.max / 10.0);

            return STR."\{min}/\{avg}/\{max}";
        }
    }
}