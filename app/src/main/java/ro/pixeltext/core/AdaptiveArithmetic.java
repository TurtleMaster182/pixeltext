package ro.pixeltext.core;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** Order-1 adaptive arithmetic coding of UTF-8 bytes; no external model needed. */
public final class AdaptiveArithmetic {
    private static final long MASK = 0xFFFFFFFFL;
    private static final long HALF = 0x80000000L;
    private static final long QUARTER = 0x40000000L;
    private static final long THREE_QUARTERS = 0xC0000000L;
    private AdaptiveArithmetic() {}

    public static byte[] encode(byte[] input, int outputLimit) {
        BitWriter bits = new BitWriter(outputLimit);
        Model[] models = new Model[257];
        long low = 0, high = MASK;
        int pending = 0, context = 256;
        for (int index = 0; index < input.length; index++) {
            if ((index & 8191) == 0) checkInterrupted();
            int symbol = input[index] & 255;
            Model model = model(models, context);
            int start = model.prefix(symbol);
            long range = high - low + 1;
            high = low + range * (start + model.frequencies[symbol]) / model.total - 1;
            low += range * start / model.total;
            while (true) {
                if (high < HALF) {
                    bits.write(0);
                    while (pending > 0) { bits.write(1); pending--; }
                } else if (low >= HALF) {
                    bits.write(1);
                    while (pending > 0) { bits.write(0); pending--; }
                    low -= HALF; high -= HALF;
                } else if (low >= QUARTER && high < THREE_QUARTERS) {
                    pending++;
                    low -= QUARTER; high -= QUARTER;
                } else break;
                low = (low << 1) & MASK;
                high = ((high << 1) | 1) & MASK;
            }
            model.update(symbol);
            context = symbol;
        }
        pending++;
        int finalBit = low < QUARTER ? 0 : 1;
        bits.write(finalBit);
        while (pending-- > 0) bits.write(1 - finalBit);
        // Explicit zero tail supplies the decoder's 32-bit look-ahead.
        for (int i = 0; i < 32; i++) bits.write(0);
        return bits.finish();
    }

    public static byte[] decode(byte[] input, int outputLength) {
        if (outputLength < 0 || outputLength > TextImageCodec.MAX_TEXT_BYTES)
            throw new IllegalArgumentException("Invalid decoded length.");
        BitReader bits = new BitReader(input);
        Model[] models = new Model[257];
        long low = 0, high = MASK, value = 0;
        for (int i = 0; i < 32; i++) value = (value << 1) | bits.read();
        byte[] output = new byte[outputLength];
        int context = 256;
        for (int index = 0; index < outputLength; index++) {
            if ((index & 8191) == 0) checkInterrupted();
            Model model = model(models, context);
            long range = high - low + 1;
            int scaled = (int) (((value - low + 1) * model.total - 1) / range);
            if (scaled < 0 || scaled >= model.total)
                throw new IllegalArgumentException("Invalid arithmetic stream.");
            int symbol = model.find(scaled);
            int start = model.prefix(symbol);
            high = low + range * (start + model.frequencies[symbol]) / model.total - 1;
            low += range * start / model.total;
            while (true) {
                if (high < HALF) {
                    // Lower half needs no subtraction.
                } else if (low >= HALF) {
                    low -= HALF; high -= HALF; value -= HALF;
                } else if (low >= QUARTER && high < THREE_QUARTERS) {
                    low -= QUARTER; high -= QUARTER; value -= QUARTER;
                } else break;
                low = (low << 1) & MASK;
                high = ((high << 1) | 1) & MASK;
                value = ((value << 1) & MASK) | bits.read();
            }
            output[index] = (byte) symbol;
            model.update(symbol);
            context = symbol;
        }
        return output;
    }

    static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted())
            throw new IllegalStateException("Operation cancelled.");
    }

    private static Model model(Model[] models, int context) {
        if (models[context] == null) models[context] = new Model();
        return models[context];
    }

    private static final class Model {
        final int[] frequencies = new int[256];
        final int[] tree = new int[257];
        int total = 256;
        Model() { Arrays.fill(frequencies, 1); rebuild(); }
        void rebuild() {
            Arrays.fill(tree, 0);
            total = 0;
            for (int symbol = 0; symbol < 256; symbol++) {
                total += frequencies[symbol];
                add(symbol, frequencies[symbol]);
            }
        }
        void add(int symbol, int count) {
            for (int i = symbol + 1; i <= 256; i += i & -i) tree[i] += count;
        }
        int prefix(int symbol) {
            int sum = 0;
            for (int i = symbol; i > 0; i -= i & -i) sum += tree[i];
            return sum;
        }
        int find(int cumulative) {
            int index = 0;
            for (int step = 256; step != 0; step >>= 1) {
                int next = index + step;
                if (next <= 256 && tree[next] <= cumulative) {
                    cumulative -= tree[next];
                    index = next;
                }
            }
            if (index >= 256) throw new IllegalArgumentException("Invalid probability index.");
            return index;
        }
        void update(int symbol) {
            frequencies[symbol]++; add(symbol, 1); total++;
            if (total >= 16384) {
                for (int i = 0; i < 256; i++) frequencies[i] = (frequencies[i] + 1) >>> 1;
                rebuild();
            }
        }
    }

    private static final class BitWriter {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final int limit;
        int buffer = 0, used = 0;
        BitWriter(int limit) { this.limit = limit; }
        void write(int bit) {
            buffer = (buffer << 1) | bit;
            if (++used == 8) { emit(); used = 0; buffer = 0; }
        }
        void emit() {
            if (output.size() >= limit) throw new TextImageCodec.CapacityException();
            output.write(buffer);
        }
        byte[] finish() {
            if (used != 0) { buffer <<= 8 - used; emit(); }
            return output.toByteArray();
        }
    }

    private static final class BitReader {
        final byte[] input;
        long position = 0;
        BitReader(byte[] input) { this.input = input; }
        int read() {
            if (position >= (long) input.length * 8)
                throw new IllegalArgumentException("Truncated arithmetic stream.");
            int bit = (input[(int) (position >>> 3)] >>> (7 - (position & 7))) & 1;
            position++;
            return bit;
        }
    }
}
