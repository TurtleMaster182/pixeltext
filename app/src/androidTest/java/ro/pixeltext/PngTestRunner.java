package ro.pixeltext;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;
import android.widget.ImageView;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import ro.pixeltext.core.TextImageCodec;
import ro.pixeltext.core.TextFileDecoder;

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
            nativeImportedRoundTrips();
            editorRoundTrip();
            status.putString("stream", "."); sendStatus(0, status);
            result.putString("stream", "\nOK (native PNG, multiline file imports and actual editor round trip)\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            status.putString("stack", error.toString()); status.putString("stream", "\n" + error);
            sendStatus(-2, status);
            result.putString("stream", "\nFAILURES: " + error + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void nativeImportedRoundTrips() throws Exception {
        String text = "First line\r\n\r\nȘtiință 日本語 🤖\nLast line\n";
        byte[] body = text.getBytes(StandardCharsets.UTF_16LE);
        byte[] file = new byte[body.length + 2]; file[0]=(byte)0xFF; file[1]=(byte)0xFE;
        System.arraycopy(body,0,file,2,body.length);
        String legacy = "Prima linie: România\r\n\r\nA doua linie: învăţare\r\n";
        for (String imported : new String[]{TextFileDecoder.decode(file).text,
                TextFileDecoder.decode(legacy.getBytes(Charset.forName("windows-1250")),"windows-1250").text}) {
            for (TextImageCodec.Mode mode : TextImageCodec.Mode.values()) {
                Bitmap image = PixelPng.create(TextImageCodec.encode(imported,mode));
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(); PixelPng.write(image,bytes);
                Bitmap reopened = PixelPng.read(new ByteArrayInputStream(bytes.toByteArray()));
                if (!imported.equals(PixelPng.decode(reopened).text)) throw new AssertionError("Multiline imported text changed in PNG.");
                image.recycle(); reopened.recycle();
            }
        }
    }

    private void editorRoundTrip() throws Exception {
        MainActivity activity = (MainActivity) startActivitySync(new Intent(getTargetContext(),MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        final String text = "First line\n\nȘtiință 日本語 🤖\nLast line\n";
        long deadline = SystemClock.uptimeMillis() + 15000;
        boolean[] ready = {false};
        while (!ready[0] && SystemClock.uptimeMillis() < deadline) {
            runOnMainSync(() -> ready[0]=activity.<EditText>findViewById(R.id.text_input).isEnabled());
            if (!ready[0]) SystemClock.sleep(100);
        }
        if (!ready[0]) throw new AssertionError("Editor did not become ready.");
        runOnMainSync(() -> {
            EditText input=activity.findViewById(R.id.text_input); input.setText(""); input.requestFocus();
            InputConnection connection=input.onCreateInputConnection(new EditorInfo());
            if (connection==null || !connection.commitText(text,1) || !text.equals(input.getText().toString()))
                throw new AssertionError("Keyboard paste did not preserve multiline text.");
            activity.findViewById(R.id.generate_png).performClick();
        });
        boolean[] generated = {false};
        deadline=SystemClock.uptimeMillis()+30000;
        while (!generated[0] && SystemClock.uptimeMillis()<deadline) {
            runOnMainSync(() -> generated[0]=activity.findViewById(R.id.encoded_image).getVisibility()==View.VISIBLE);
            if (!generated[0]) SystemClock.sleep(100);
        }
        if (!generated[0]) throw new AssertionError("PNG generation failed from multiline editor text.");
        runOnMainSync(() -> {
            ImageView preview=activity.findViewById(R.id.encoded_image);
            Bitmap image=((BitmapDrawable)preview.getDrawable()).getBitmap();
            if (!text.equals(PixelPng.decode(image).text)) throw new AssertionError("Editor PNG lost line breaks.");
            activity.finish();
        });
    }
    private static String repeated() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 2000; i++) text.append("Compression learns probabilities. ");
        return text.toString();
    }
}
