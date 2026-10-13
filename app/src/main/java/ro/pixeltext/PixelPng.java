package ro.pixeltext;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorSpace;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import ro.pixeltext.core.TextImageCodec;

/** Exact opaque sRGB transport. Never resize, filter or quantize payload pixels. */
public final class PixelPng {
    private static final byte[] SIGNATURE = {(byte)137,80,78,71,13,10,26,10};
    private static final int MAX_PNG_BYTES = 4 * 1024 * 1024;
    private PixelPng() {}

    public static Bitmap create(TextImageCodec.Encoded encoded) {
        return Bitmap.createBitmap(TextImageCodec.toPixels(encoded), TextImageCodec.SIDE,
                TextImageCodec.SIDE, Bitmap.Config.ARGB_8888);
    }

    public static void write(Bitmap bitmap, OutputStream output) throws IOException {
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            throw new IOException("PNG encoding failed.");
        output.flush();
    }

    public static Bitmap read(InputStream stream) throws IOException {
        byte[] bytes = readBounded(stream, MAX_PNG_BYTES);
        if (bytes.length < 8 || !Arrays.equals(SIGNATURE, Arrays.copyOf(bytes, 8)))
            throw new IOException("Select the original PNG file, not a JPEG or screenshot.");
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth != 512 || bounds.outHeight != 512)
            throw new IOException("The image must be exactly 512×512 pixels.");
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
        options.inMutable = true;
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (bitmap == null) throw new IOException("PNG could not be opened.");
        return bitmap;
    }

    public static TextImageCodec.Decoded decode(Bitmap bitmap) {
        int[] pixels = new int[512 * 512];
        bitmap.getPixels(pixels, 0, 512, 0, 0, 512, 512);
        return TextImageCodec.fromPixels(pixels, bitmap.getWidth(), bitmap.getHeight());
    }

    static byte[] readBounded(InputStream stream, int limit) throws IOException {
        if (stream == null) throw new IOException("File could not be opened.");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Operation cancelled.");
            if (output.size() + count > limit) throw new IOException("File exceeds the supported size limit.");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
}
