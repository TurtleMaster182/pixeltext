package ro.pixeltext;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.os.Bundle;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import ro.pixeltext.core.TextImageCodec;

/** Native Android PNG integration test with the standard instrumentation status protocol. */
public final class PngTestRunner extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle status = new Bundle();
        status.putString("id", "InstrumentationTestRunner");
        status.putString("class", "ro.pixeltext.PngTestRunner");
        status.putString("test", "nativePngRoundTrip");
        status.putInt("numtests", 1); status.putInt("current", 1);
        sendStatus(1, status);
        Bundle result = new Bundle();
        try {
            for (String text : new String[]{"", "Știință 日本語 한글 🤖\n", repeated()}) {
                for (TextImageCodec.Mode mode : TextImageCodec.Mode.values()) {
                    Bitmap bitmap = PixelPng.create(TextImageCodec.encode(text, mode));
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    PixelPng.write(bitmap, bytes);
                    Bitmap reopened = PixelPng.read(new ByteArrayInputStream(bytes.toByteArray()));
                    if (reopened.getWidth() != 512 || reopened.getHeight() != 512
                            || !text.equals(PixelPng.decode(reopened).text))
                        throw new AssertionError("Android PNG round trip did not preserve text.");
                    bitmap.recycle(); reopened.recycle();
                }
            }
            status.putString("stream", "."); sendStatus(0, status);
            result.putString("stream", "\nOK (1 test, 9 native PNG round trips)\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            status.putString("stack", error.toString()); status.putString("stream", "\n" + error);
            sendStatus(-2, status);
            result.putString("stream", "\nFAILURES: " + error + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    private static String repeated() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 2000; i++) text.append("Compression learns probabilities. ");
        return text.toString();
    }
}
