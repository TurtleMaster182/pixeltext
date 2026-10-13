package ro.pixeltext.core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.CRC32;

/** Dependency-free correctness and hostile-input tests; run with scripts/test-codec.sh. */
public final class CodecSelfTest {
    private static int checks;
    private static final Random RANDOM = new Random(19044);

    public static void main(String[] args) {
        String[] examples = {"", "a", "aaaa", "Hello, world!", "\u0000\r\n\t end ",
            "Știință și creație: ăâîșț ĂÂÎȘȚ", "你好世界 日本語 한글", "🤖🌶️🚀 🧑‍💻 café e\u0301", "a\r\nb\nc\r"};
        for (String text : examples) for (TextImageCodec.Mode mode : TextImageCodec.Mode.values()) roundTrip(text, mode);

        for (int length : new int[]{0,1,2,3,7,8,15,31,255,256,257,1024,8192,20000,100000}) {
            byte[] bytes = new byte[length]; RANDOM.nextBytes(bytes); arithmetic(bytes);
            Arrays.fill(bytes, (byte) 0); arithmetic(bytes);
            Arrays.fill(bytes, (byte) 255); arithmetic(bytes);
            for (int i = 0; i < length; i++) bytes[i] = (byte) i;
            arithmetic(bytes);
        }
        for (int iteration = 0; iteration < 250; iteration++) {
            byte[] bytes = new byte[RANDOM.nextInt(5000)]; RANDOM.nextBytes(bytes); arithmetic(bytes);
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < 120 + iteration; i++) {
                int cp = RANDOM.nextInt(0x110000);
                if (cp >= 0xD800 && cp <= 0xDFFF) cp = 'x';
                text.appendCodePoint(cp);
            }
            roundTrip(text.toString(), TextImageCodec.Mode.values()[iteration % 3]);
        }

        String repeat = "The next symbol depends on the previous symbol. Știință 🤖.\n".repeat(12000);
        for (TextImageCodec.Mode mode : TextImageCodec.Mode.values()) roundTrip(repeat, mode);
        roundTrip("a".repeat(TextImageCodec.MAX_TEXT_BYTES), TextImageCodec.Mode.AUTO);
        reject(() -> TextImageCodec.encode("a".repeat(TextImageCodec.MAX_TEXT_BYTES + 1), TextImageCodec.Mode.AUTO));
        reject(() -> TextImageCodec.encode("🤖".repeat(TextImageCodec.MAX_TEXT_BYTES / 4 + 1), TextImageCodec.Mode.AUTO));
        reject(() -> TextImageCodec.encode("\uD800", TextImageCodec.Mode.AUTO));

        byte[] rawAtLimit = new byte[TextImageCodec.CAPACITY];
        RANDOM.nextBytes(rawAtLimit);
        reject(() -> AdaptiveArithmetic.encode(rawAtLimit, 8));
        byte[] fixture = TextImageCodec.encode(repeat, TextImageCodec.Mode.AUTO).container;
        byte[] headerCorrupt = fixture.clone(); headerCorrupt[12] ^= 1;
        reject(() -> TextImageCodec.decode(headerCorrupt));
        byte[] payloadCorrupt = fixture.clone(); payloadCorrupt[40] ^= 16;
        reject(() -> TextImageCodec.decode(payloadCorrupt));
        reject(() -> TextImageCodec.decode(Arrays.copyOf(fixture, fixture.length - 1)));
        byte[] unknownVersion = fixture.clone(); unknownVersion[8] = 99; checksumHeader(unknownVersion);
        reject(() -> TextImageCodec.decode(unknownVersion));
        byte[] largeLength = fixture.clone(); ByteBuffer.wrap(largeLength).putInt(12, Integer.MAX_VALUE); checksumHeader(largeLength);
        reject(() -> TextImageCodec.decode(largeLength));
        byte[] negativeLength = fixture.clone(); ByteBuffer.wrap(negativeLength).putInt(16, -1); checksumHeader(negativeLength);
        reject(() -> TextImageCodec.decode(negativeLength));
        byte[] badCodec = fixture.clone(); badCodec[9] = 7; checksumHeader(badCodec);
        reject(() -> TextImageCodec.decode(badCodec));
        byte[] badTextCrc = fixture.clone(); badTextCrc[20] ^= 1; checksumHeader(badTextCrc);
        reject(() -> TextImageCodec.decode(badTextCrc));

        // A valid compressed stream with a maliciously undersized declared output is bounded.
        byte[] bomb = fixture.clone(); ByteBuffer.wrap(bomb).putInt(12, 10); checksumHeader(bomb);
        reject(() -> TextImageCodec.decode(bomb));
        byte[] badUtf8 = rawContainer(new byte[]{(byte) 0xFF});
        reject(() -> TextImageCodec.decode(badUtf8));
        int[] transparent = TextImageCodec.toPixels(TextImageCodec.encode("hello", TextImageCodec.Mode.AUTO));
        transparent[12] &= 0x00FFFFFF;
        reject(() -> TextImageCodec.fromPixels(transparent, 512, 512));
        reject(() -> TextImageCodec.fromPixels(new int[256], 16, 16));
        reject(() -> AdaptiveArithmetic.decode(new byte[1], 4));

        // The full 786,400 byte payload boundary fits exactly; one extra byte does not.
        byte[] full = new byte[TextImageCodec.CAPACITY]; Arrays.fill(full, (byte) 'x');
        String recovered = TextImageCodec.decode(rawContainer(full)).text;
        check(recovered.length() == TextImageCodec.CAPACITY, "Exact capacity boundary");
        byte[] oversized = rawContainer(new byte[TextImageCodec.CAPACITY + 1]);
        reject(() -> TextImageCodec.decode(oversized));

        for (TextImageCodec.Mode mode : TextImageCodec.Mode.values()) {
            TextImageCodec.Encoded encoded = TextImageCodec.encode(repeat, mode);
            System.out.printf("%s: %,d → %,d bytes (%.2f%% saved)%n", mode, encoded.originalBytes,
                encoded.payloadBytes, 100.0 * (1.0 - (double) encoded.payloadBytes / encoded.originalBytes));
        }
        System.out.println("PASS: " + checks + " codec checks; Unicode, model rescaling, pixels, corruption, 8 MiB and capacity boundaries.");
    }

    private static void arithmetic(byte[] bytes) {
        byte[] compressed = AdaptiveArithmetic.encode(bytes, TextImageCodec.CAPACITY);
        check(Arrays.equals(bytes, AdaptiveArithmetic.decode(compressed, bytes.length)), "Arithmetic round trip");
    }
    private static void roundTrip(String text, TextImageCodec.Mode mode) {
        TextImageCodec.Encoded encoded = TextImageCodec.encode(text, mode);
        check(TextImageCodec.decode(encoded.container).text.equals(text), "Container round trip");
        check(TextImageCodec.fromPixels(TextImageCodec.toPixels(encoded), 512, 512).text.equals(text), "Pixel round trip");
        check(encoded.payloadBytes <= TextImageCodec.CAPACITY, "Capacity");
        if (mode == TextImageCodec.Mode.AUTO && encoded.originalBytes <= TextImageCodec.CAPACITY)
            check(encoded.payloadBytes <= encoded.originalBytes, "Automatic mode never expands payload");
    }
    private static void reject(Runnable operation) {
        try { operation.run(); throw new AssertionError("Invalid input was accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
        checks++;
    }
    private static byte[] rawContainer(byte[] bytes) {
        byte[] result = new byte[32 + bytes.length];
        ByteBuffer header = ByteBuffer.wrap(result);
        header.put("PXTEXT01".getBytes(StandardCharsets.US_ASCII)).put((byte) 1).put((byte) 0).putShort((short) 0);
        header.putInt(bytes.length).putInt(bytes.length);
        CRC32 crc = new CRC32(); crc.update(bytes);
        header.putInt((int) crc.getValue()).putInt((int) crc.getValue());
        checksumHeader(result); System.arraycopy(bytes, 0, result, 32, bytes.length);
        return result;
    }
    private static void checksumHeader(byte[] bytes) {
        CRC32 crc = new CRC32(); crc.update(bytes, 0, 28);
        ByteBuffer.wrap(bytes).putInt(28, (int) crc.getValue());
    }
}
