package ro.pixeltext.core;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Strict text-file import. A byte-order mark identifies UTF-8 or UTF-16; legacy encodings are explicit. */
public final class TextFileDecoder {
    private TextFileDecoder() {}

    public static final class Imported {
        public final String text, encoding;
        private Imported(String text, Charset encoding) { this.text = text; this.encoding = encoding.name(); }
    }

    public static Imported decode(byte[] bytes) throws CharacterCodingException {
        if (bytes == null) throw new IllegalArgumentException("No text file was supplied.");
        if (starts(bytes, 0xEF, 0xBB, 0xBF)) return decode(bytes, StandardCharsets.UTF_8, 3);
        if (starts(bytes, 0xFF, 0xFE, 0x00, 0x00) || starts(bytes, 0x00, 0x00, 0xFE, 0xFF))
            throw new IllegalArgumentException("UTF-32 text is not supported. Save the file as UTF-8 or UTF-16.");
        if (starts(bytes, 0xFF, 0xFE)) return decode(bytes, StandardCharsets.UTF_16LE, 2);
        if (starts(bytes, 0xFE, 0xFF)) return decode(bytes, StandardCharsets.UTF_16BE, 2);
        return decode(bytes, StandardCharsets.UTF_8, 0);
    }

    public static Imported decode(byte[] bytes, String encoding) throws CharacterCodingException {
        Charset charset = Charset.forName(encoding);
        int skip = 0;
        if (charset.equals(StandardCharsets.UTF_8) && starts(bytes, 0xEF, 0xBB, 0xBF)) skip = 3;
        else if (charset.equals(StandardCharsets.UTF_16LE) && starts(bytes, 0xFF, 0xFE)) skip = 2;
        else if (charset.equals(StandardCharsets.UTF_16BE) && starts(bytes, 0xFE, 0xFF)) skip = 2;
        return decode(bytes, charset, skip);
    }

    private static Imported decode(byte[] bytes, Charset charset, int skip) throws CharacterCodingException {
        if (bytes == null || bytes.length > TextImageCodec.MAX_TEXT_BYTES)
            throw new IllegalArgumentException("Text file exceeds the 8 MiB input limit.");
        String text = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, skip, bytes.length - skip)).toString();
        // Import must also respect the UTF-8 limit used by the image format.
        ByteBuffer utf8 = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text));
        if (utf8.remaining() > TextImageCodec.MAX_TEXT_BYTES)
            throw new IllegalArgumentException("Decoded text exceeds the 8 MiB UTF-8 input limit.");
        return new Imported(text, charset);
    }

    private static boolean starts(byte[] bytes, int... prefix) {
        if (bytes == null || bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if ((bytes[i] & 255) != prefix[i]) return false;
        return true;
    }
}
