package ro.pixeltext.core;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Android-independent format/codec. All lengths are UTF-8 byte lengths. */
public final class TextImageCodec {
    public static final int SIDE = 512;
    public static final int HEADER_BYTES = 32;
    public static final int CAPACITY = SIDE * SIDE * 3 - HEADER_BYTES;
    public static final int MAX_TEXT_BYTES = 8 * 1024 * 1024;
    private static final byte[] MAGIC = "PXTEXT01".getBytes(StandardCharsets.US_ASCII);
    private static final int RAW = 0, ARITHMETIC = 1, DEFLATE = 2;
    private TextImageCodec() {}

    public enum Mode { AUTO, PREDICTIVE, DEFLATE }

    public static final class Encoded {
        public final byte[] container;
        public final int originalBytes, payloadBytes;
        public final String algorithm;
        private Encoded(byte[] container, int originalBytes, int payloadBytes, int codec) {
            this.container = container;
            this.originalBytes = originalBytes;
            this.payloadBytes = payloadBytes;
            this.algorithm = algorithmName(codec);
        }
    }

    public static final class Decoded {
        public final String text, algorithm;
        public final int originalBytes, payloadBytes;
        private Decoded(String text, int originalBytes, int payloadBytes, int codec) {
            this.text = text; this.originalBytes = originalBytes;
            this.payloadBytes = payloadBytes; this.algorithm = algorithmName(codec);
        }
    }

    public static final class CapacityException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        public CapacityException() {
            super("Text does not fit in one 512×512 image. Try Automatic mode or shorten the text.");
        }
    }

    public static Encoded encode(String text, Mode mode) {
        if (text == null || mode == null) throw new IllegalArgumentException("Missing text or mode.");
        if (text.length() > MAX_TEXT_BYTES) throw new IllegalArgumentException("Text exceeds the 8 MiB input limit.");
        byte[] original;
        try {
            ByteBuffer buffer = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text));
            original = new byte[buffer.remaining()]; buffer.get(original);
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("Text contains an invalid Unicode sequence.", error);
        }
        if (original.length > MAX_TEXT_BYTES) throw new IllegalArgumentException("Text exceeds the 8 MiB input limit.");
        byte[] payload = null;
        int codec = RAW;
        if (mode == Mode.AUTO && original.length <= CAPACITY) payload = original;
        if (mode != Mode.DEFLATE) {
            try {
                byte[] predictive = AdaptiveArithmetic.encode(original, CAPACITY);
                if (payload == null || predictive.length < payload.length || mode == Mode.PREDICTIVE) {
                    payload = predictive; codec = ARITHMETIC;
                }
            } catch (CapacityException error) {
                if (mode == Mode.PREDICTIVE) throw error;
            }
        }
        if (mode != Mode.PREDICTIVE) {
            try {
                byte[] deflated = deflate(original);
                if (payload == null || deflated.length < payload.length || mode == Mode.DEFLATE) {
                    payload = deflated; codec = DEFLATE;
                }
            } catch (CapacityException error) {
                if (mode == Mode.DEFLATE) throw error;
            }
        }
        if (payload == null || payload.length > CAPACITY) throw new CapacityException();
        byte[] container = new byte[HEADER_BYTES + payload.length];
        ByteBuffer header = ByteBuffer.wrap(container).order(ByteOrder.BIG_ENDIAN);
        header.put(MAGIC).put((byte) 1).put((byte) codec).putShort((short) 0);
        header.putInt(original.length).putInt(payload.length);
        header.putInt(crc(original, 0, original.length));
        header.putInt(crc(payload, 0, payload.length));
        header.putInt(crc(container, 0, 28));
        System.arraycopy(payload, 0, container, HEADER_BYTES, payload.length);
        return new Encoded(container, original.length, payload.length, codec);
    }

    public static Decoded decode(byte[] container) {
        if (container == null || container.length < HEADER_BYTES) throw invalid("Incomplete header.");
        if (!Arrays.equals(MAGIC, Arrays.copyOf(container, MAGIC.length)))
            throw invalid("This image is not a PixelText image.");
        ByteBuffer header = ByteBuffer.wrap(container).order(ByteOrder.BIG_ENDIAN);
        header.position(8);
        int version = header.get() & 255, codec = header.get() & 255;
        int flags = header.getShort() & 65535;
        int originalLength = header.getInt(), payloadLength = header.getInt();
        int textCrc = header.getInt(), payloadCrc = header.getInt(), headerCrc = header.getInt();
        if (crc(container, 0, 28) != headerCrc) throw invalid("Header checksum failed. Image was modified.");
        if (version != 1 || flags != 0 || codec > DEFLATE) throw invalid("Unsupported PixelText format.");
        if (originalLength < 0 || originalLength > MAX_TEXT_BYTES || payloadLength < 0 || payloadLength > CAPACITY)
            throw invalid("Invalid lengths in image header.");
        if (container.length < HEADER_BYTES + payloadLength) throw invalid("Truncated image payload.");
        if (crc(container, HEADER_BYTES, payloadLength) != payloadCrc)
            throw invalid("Payload checksum failed. Send the original PNG as a file.");
        byte[] payload = Arrays.copyOfRange(container, HEADER_BYTES, HEADER_BYTES + payloadLength);
        byte[] original;
        switch (codec) {
            case ARITHMETIC: original = AdaptiveArithmetic.decode(payload, originalLength); break;
            case DEFLATE: original = inflate(payload, originalLength); break;
            default:
                if (payloadLength != originalLength) throw invalid("Invalid raw payload length.");
                original = payload;
        }
        if (crc(original, 0, original.length) != textCrc) throw invalid("Text checksum failed.");
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(original)).toString();
            return new Decoded(text, originalLength, payloadLength, codec);
        } catch (CharacterCodingException error) {
            throw invalid("Decoded text is not valid UTF-8.");
        }
    }

    public static int[] toPixels(Encoded encoded) {
        int[] pixels = new int[SIDE * SIDE];
        Arrays.fill(pixels, 0xFF161A25);
        byte[] data = encoded.container;
        for (int i = 0; i < (data.length + 2) / 3; i++) {
            int offset = i * 3;
            int red = data[offset] & 255;
            int green = offset + 1 < data.length ? data[offset + 1] & 255 : 0;
            int blue = offset + 2 < data.length ? data[offset + 2] & 255 : 0;
            pixels[i] = 0xFF000000 | (red << 16) | (green << 8) | blue;
        }
        return pixels;
    }

    public static Decoded fromPixels(int[] pixels, int width, int height) {
        if (width != SIDE || height != SIDE || pixels == null || pixels.length != SIDE * SIDE)
            throw invalid("PixelText requires an original 512×512 PNG.");
        byte[] data = new byte[SIDE * SIDE * 3];
        for (int i = 0; i < pixels.length; i++) {
            if ((pixels[i] >>> 24) != 255) throw invalid("Image contains transparency or modified pixels.");
            data[i * 3] = (byte) (pixels[i] >>> 16);
            data[i * 3 + 1] = (byte) (pixels[i] >>> 8);
            data[i * 3 + 2] = (byte) pixels[i];
        }
        return decode(data);
    }

    private static byte[] deflate(byte[] input) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(input); deflater.finish();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                AdaptiveArithmetic.checkInterrupted();
                int count = deflater.deflate(buffer);
                if (count == 0 && !deflater.finished()) throw invalid("Compression stalled.");
                if (output.size() + count > CAPACITY) throw new CapacityException();
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally { deflater.end(); }
    }

    private static byte[] inflate(byte[] input, int expectedLength) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(input);
            byte[] output = new byte[expectedLength], buffer = new byte[8192];
            int position = 0;
            while (!inflater.finished()) {
                AdaptiveArithmetic.checkInterrupted();
                int count = inflater.inflate(buffer);
                if (position + count > expectedLength) throw invalid("Decompressed data exceeds declared size.");
                System.arraycopy(buffer, 0, output, position, count);
                position += count;
                if (count == 0 && !inflater.finished()) throw invalid("Incomplete compressed stream.");
            }
            if (position != expectedLength || inflater.getRemaining() != 0)
                throw invalid("Compressed stream length mismatch.");
            return output;
        } catch (DataFormatException error) {
            throw invalid("Invalid compressed stream.");
        } finally { inflater.end(); }
    }

    private static int crc(byte[] bytes, int offset, int length) {
        CRC32 crc = new CRC32(); crc.update(bytes, offset, length); return (int) crc.getValue();
    }
    private static String algorithmName(int codec) {
        switch (codec) {
            case ARITHMETIC: return "Adaptive arithmetic · order 1";
            case DEFLATE: return "DEFLATE · dictionary + Huffman";
            default: return "Uncompressed · smallest result";
        }
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
