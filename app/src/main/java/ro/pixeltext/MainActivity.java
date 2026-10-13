package ro.pixeltext;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import ro.pixeltext.core.TextImageCodec;
import ro.pixeltext.core.TextFileDecoder;

/** Native Android app. All compression and file access run on a worker thread. */
public final class MainActivity extends Activity {
    private static final int OPEN_TEXT = 10, OPEN_IMAGE = 11, SAVE_IMAGE = 12, SAVE_TEXT = 13;
    private static final int BG = Color.rgb(16,18,26), CARD = Color.rgb(25,29,40);
    private static final int FG = Color.rgb(239,242,250), MUTED = Color.rgb(161,172,194);
    private static final int LIME = Color.rgb(163,230,53), PURPLE = Color.rgb(167,139,250);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayList<View> controls = new ArrayList<>();
    private LinearLayout encodePage, decodePage;
    private EditText input;
    private Spinner mode;
    private TextView inputInfo, encodeInfo, decodeInfo, output;
    private Button encodeTab, decodeTab, saveImage, shareImage, copyText, saveText;
    private ImageView encodePreview, decodePreview;
    private ProgressBar progress;
    private TextView status;
    private String currentPngName;
    private String decodedText;
    private int selectedTab = 0;
    private int lastMode = 0;
    private boolean busy, restoring;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = column(0);
        root.setBackgroundColor(BG);
        root.setPadding(dp(22), dp(18), dp(22), dp(18));
        // System bars and IME are accounted for on edge-to-edge Android versions.
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(dp(22) + insets.getSystemWindowInsetLeft(),
                    dp(18) + insets.getSystemWindowInsetTop(),
                    dp(22) + insets.getSystemWindowInsetRight(),
                    dp(18) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root);
        TextView eyebrow = label("TEXT → PIXELS → TEXT", 11, LIME);
        eyebrow.setLetterSpacing(0.17f); root.addView(eyebrow);
        TextView title = label("PixelText", 34, FG);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD); root.addView(title);
        root.addView(label("A whole document. One tiny image.", 14, MUTED));

        LinearLayout tabs = row(); tabs.setPadding(0, dp(18), 0, dp(14));
        encodeTab = button("Encode", false, v -> switchTab(0));
        decodeTab = button("Decode", false, v -> switchTab(1));
        addHalf(tabs, encodeTab); addHalf(tabs, decodeTab); root.addView(tabs);

        LinearLayout workStatus = row(); workStatus.setGravity(Gravity.CENTER_VERTICAL);
        progress = new ProgressBar(this); progress.setVisibility(View.GONE);
        workStatus.addView(progress, new LinearLayout.LayoutParams(dp(22), dp(22)));
        status = label("Offline · lossless · 512 × 512", 12, MUTED);
        status.setPadding(dp(8), 0, 0, dp(10)); workStatus.addView(status);
        root.addView(workStatus);

        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout pages = column(0); scroll.addView(pages);
        encodePage = column(0); decodePage = column(0);
        pages.addView(encodePage); pages.addView(decodePage);
        buildEncode(); buildDecode();

        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                inputInfo.setText(String.format(Locale.ROOT, "%,d characters · 8 MiB UTF-8 input limit", s.length()));
                if (!restoring) invalidateEncoded();
            }
            public void afterTextChanged(Editable e) {}
        });
        mode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position != lastMode && !restoring) invalidateEncoded();
                lastMode = position;
            }
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        selectedTab = state == null ? 0 : state.getInt("tab", 0);
        lastMode = state == null ? 0 : state.getInt("mode", 0);
        mode.setSelection(lastMode);
        switchTab(selectedTab);
        updateControls();
        currentPngName = state == null ? null : state.getString("png");
        restoreDraft();
    }

    private void buildEncode() {
        LinearLayout textCard = card();
        textCard.addView(label("01 / TEXT", 12, LIME));
        input = new EditText(this); input.setHint("Type or paste text…");
        input.setId(R.id.text_input);
        input.setTextColor(FG); input.setHintTextColor(MUTED); input.setTextSize(16);
        input.setGravity(Gravity.TOP); input.setMinLines(6);
        input.setMaxLines(12); input.setSaveEnabled(false);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(TextImageCodec.MAX_TEXT_BYTES)});
        textCard.addView(input, new LinearLayout.LayoutParams(-1, -2)); controls.add(input);
        inputInfo = label("0 characters · 8 MiB UTF-8 input limit", 11, MUTED);
        textCard.addView(inputInfo);
        LinearLayout inputActions = row();
        addHalf(inputActions, button("Import .txt", false, v -> openDocument(OPEN_TEXT, "text/*")));
        addHalf(inputActions, button("Example", false, v -> input.setText(R.string.example)));
        textCard.addView(inputActions); encodePage.addView(textCard);

        LinearLayout compression = card(); compression.addView(label("02 / COMPRESSION", 12, LIME));
        mode = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{"Automatic · smallest result", "Predictive · adaptive arithmetic", "DEFLATE · dictionary + Huffman"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mode.setAdapter(adapter); controls.add(mode); compression.addView(mode);
        compression.addView(label("Predictive mode learns next-byte probabilities. Automatic mode compares both compressors and raw text.", 13, MUTED));
        Button generate = button("Generate 512 × 512 PNG", true, v -> generate());
        generate.setId(R.id.generate_png); compression.addView(generate);
        encodePage.addView(compression);

        LinearLayout result = card(); result.addView(label("03 / IMAGE", 12, LIME));
        encodePreview = preview(); result.addView(encodePreview);
        encodePreview.setId(R.id.encoded_image);
        encodeInfo = label("786,400 bytes of compressed payload per image. Capacity depends on the text.", 13, MUTED);
        result.addView(encodeInfo);
        LinearLayout exports = row();
        saveImage = button("Save PNG", true, v -> createDocument(SAVE_IMAGE, "image/png", "pixeltext.png"));
        shareImage = button("Share file", false, v -> share());
        addHalf(exports, saveImage); addHalf(exports, shareImage); result.addView(exports);
        result.addView(label("Keep the original PNG. Resizing, editing, screenshots and JPEG conversion destroy stored data. Share as a file.", 12, MUTED));
        encodePage.addView(result);
    }

    private void buildDecode() {
        LinearLayout source = card(); source.addView(label("01 / ORIGINAL PNG", 12, PURPLE));
        source.addView(label("Open a PixelText image to recover the complete text.", 15, FG));
        source.addView(button("Open PNG & decode", true, v -> openDocument(OPEN_IMAGE, "image/png")));
        decodePreview = preview(); source.addView(decodePreview);
        decodeInfo = label("Checksums verify the image and recovered text.", 13, MUTED);
        source.addView(decodeInfo); decodePage.addView(source);

        LinearLayout recovered = card(); recovered.addView(label("02 / RECOVERED TEXT", 12, PURPLE));
        output = label("Decoded text appears here.", 16, FG); output.setTextIsSelectable(true);
        output.setSaveEnabled(false); output.setPadding(0, dp(14), 0, dp(18));
        recovered.addView(output);
        LinearLayout actions = row();
        copyText = button("Copy text", false, v -> {
            if (decodedText == null) return;
            if (decodedText.getBytes(StandardCharsets.UTF_8).length > 200 * 1024) {
                error("Text is too large for reliable clipboard transfer. Save a .txt file instead."); return;
            }
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("PixelText", decodedText)); toast("Text copied.");
        });
        saveText = button("Save .txt", true, v -> createDocument(SAVE_TEXT, "text/plain", "decoded.txt"));
        addHalf(actions, copyText); addHalf(actions, saveText); recovered.addView(actions);
        decodePage.addView(recovered);
    }

    private void generate() {
        final String text = input.getText().toString();
        final TextImageCodec.Mode selected = TextImageCodec.Mode.values()[mode.getSelectedItemPosition()];
        final String filename = "pixeltext-" + UUID.randomUUID() + ".png";
        begin("Learning probabilities & compressing…");
        worker.execute(() -> {
            try {
                TextImageCodec.Encoded result = TextImageCodec.encode(text, selected);
                Bitmap image = PixelPng.create(result);
                // Verify actual Android PNG bytes before offering the file for export.
                File png = new File(getCacheDir(), filename);
                try (OutputStream stream = new FileOutputStream(png)) { PixelPng.write(image, stream); }
                try (InputStream stream = new FileInputStream(png)) {
                    Bitmap roundTrip = PixelPng.read(stream);
                    try {
                        if (!text.equals(PixelPng.decode(roundTrip).text)) throw new IOException("PNG round-trip verification failed.");
                    } finally { roundTrip.recycle(); }
                }
                writeDraft(text);
                ui(() -> {
                    currentPngName = filename;
                    encodePreview.setImageBitmap(image); encodePreview.setVisibility(View.VISIBLE);
                    double saved = result.originalBytes == 0 ? 0 : (1.0 - (double) result.payloadBytes / result.originalBytes) * 100;
                    encodeInfo.setText(String.format(Locale.ROOT,
                        "%s\nUTF-8: %,d B → %,d B · %.1f%% saved\nImage capacity: %.2f%% · PNG file: %,d B\nExact PNG round trip verified.",
                        result.algorithm, result.originalBytes, result.payloadBytes, saved,
                        100.0 * result.payloadBytes / TextImageCodec.CAPACITY, png.length()));
                    finish("PNG ready · round trip verified");
                });
            } catch (Exception error) { failure(error); }
        });
    }

    private void importText(Uri uri) {
        begin("Reading UTF-8 text…");
        worker.execute(() -> {
            try (InputStream stream = getContentResolver().openInputStream(uri)) {
                byte[] bytes = PixelPng.readBounded(stream, TextImageCodec.MAX_TEXT_BYTES);
                try {
                    TextFileDecoder.Imported imported = TextFileDecoder.decode(bytes);
                    ui(() -> { input.setText(imported.text); finish("Text imported · " + imported.encoding); });
                } catch (CharacterCodingException error) {
                    ui(() -> { finish("Select text encoding"); chooseTextEncoding(bytes); });
                }
            } catch (Exception error) { failure(error); }
        });
    }

    private void chooseTextEncoding(byte[] bytes) {
        final String[] encodings = {"windows-1250", "windows-1252", "ISO-8859-2", "UTF-16LE", "UTF-16BE"};
        String[] labels = {"Windows-1250 · Romanian / Central Europe", "Windows-1252 · Western Europe",
            "ISO-8859-2 · Central Europe", "UTF-16 little-endian", "UTF-16 big-endian"};
        new AlertDialog.Builder(this).setTitle(R.string.text_encoding_title)
            .setItems(labels, (dialog, selected) -> {
                begin("Reading text file…");
                worker.execute(() -> {
                    try {
                        TextFileDecoder.Imported imported = TextFileDecoder.decode(bytes, encodings[selected]);
                        ui(() -> { input.setText(imported.text); finish("Text imported · " + imported.encoding); });
                    } catch (CharacterCodingException error) {
                        ui(() -> { finish("Import failed"); error(getString(R.string.text_encoding_error)); });
                    } catch (Exception error) { failure(error); }
                });
            }).setNegativeButton("Cancel", null).show();
    }

    private void decode(Uri uri) {
        begin("Verifying PNG & decoding…");
        decodedText = null; output.setText(R.string.decoding); decodeInfo.setText("");
        decodePreview.setImageDrawable(null); decodePreview.setVisibility(View.GONE);
        worker.execute(() -> {
            try (InputStream stream = getContentResolver().openInputStream(uri)) {
                Bitmap image = PixelPng.read(stream);
                TextImageCodec.Decoded result;
                try { result = PixelPng.decode(image); }
                catch (Exception error) { image.recycle(); throw error; }
                try (OutputStream saved = new FileOutputStream(new File(getFilesDir(), "decoded.txt"))) {
                    saved.write(result.text.getBytes(StandardCharsets.UTF_8));
                }
                ui(() -> {
                    decodedText = result.text;
                    // Large documents stay available in full for .txt export without overwhelming a TextView.
                    output.setText(result.text.length() > 100000
                        ? result.text.substring(0, 100000) + "\n\n[Preview limited to 100,000 UTF-16 characters. Save .txt for the complete text.]"
                        : result.text.isEmpty() ? "[Empty text]" : result.text);
                    decodePreview.setImageBitmap(image); decodePreview.setVisibility(View.VISIBLE);
                    decodeInfo.setText(String.format(Locale.ROOT, "%s\n%,d compressed B → %,d UTF-8 B\nAll checksums passed.",
                        result.algorithm, result.payloadBytes, result.originalBytes));
                    finish("Decoded · integrity verified");
                });
            } catch (Exception error) {
                ui(() -> { output.setText(R.string.no_recovered_text); decodeInfo.setText(R.string.decode_failed); });
                failure(error);
            }
        });
    }

    private void export(Uri uri, boolean image) {
        final String text = decodedText;
        final String pngName = currentPngName;
        begin("Saving file…");
        worker.execute(() -> {
            try (OutputStream destination = getContentResolver().openOutputStream(uri, "wt")) {
                if (destination == null) throw new IOException("Destination could not be opened.");
                if (image) {
                    if (pngName == null) throw new IOException("Generate a PNG before exporting.");
                    try (InputStream source = new FileInputStream(new File(getCacheDir(), pngName))) {
                        byte[] buffer = new byte[8192]; int count;
                        while ((count = source.read(buffer)) != -1) destination.write(buffer, 0, count);
                    }
                } else {
                    if (text != null) destination.write(text.getBytes(StandardCharsets.UTF_8));
                    else {
                        File recovered = new File(getFilesDir(), "decoded.txt");
                        if (!recovered.exists()) throw new IOException("Open the PNG again before exporting recovered text.");
                        try (InputStream saved = new FileInputStream(recovered)) {
                            destination.write(PixelPng.readBounded(saved, TextImageCodec.MAX_TEXT_BYTES));
                        }
                    }
                }
                destination.flush();
                ui(() -> { finish("File saved"); toast("File saved."); });
            } catch (Exception error) { failure(error); }
        });
    }

    private void share() {
        if (currentPngName == null) return;
        Uri uri = Uri.parse("content://" + getPackageName() + ".files/" + currentPngName);
        Intent intent = new Intent(Intent.ACTION_SEND).setType("image/png");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.setClipData(ClipData.newRawUri("PixelText PNG", uri));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, "Send the original PNG as a file"));
    }

    private void openDocument(int request, String mime) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(mime);
        intent.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(intent, request);
    }
    private void createDocument(int request, String mime, String name) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(mime);
        intent.addCategory(Intent.CATEGORY_OPENABLE); intent.putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(intent, request);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == OPEN_TEXT) importText(uri);
        else if (request == OPEN_IMAGE) decode(uri);
        else if (request == SAVE_IMAGE) export(uri, true);
        else if (request == SAVE_TEXT) export(uri, false);
    }

    private void invalidateEncoded() {
        currentPngName = null;
        encodePreview.setImageDrawable(null); encodePreview.setVisibility(View.GONE);
        encodeInfo.setText(R.string.generate_current_text);
        updateControls();
    }
    private void switchTab(int tab) {
        selectedTab = tab; encodePage.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        decodePage.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        encodeTab.setTextColor(tab == 0 ? BG : FG); decodeTab.setTextColor(tab == 1 ? BG : FG);
        encodeTab.setBackground(shape(tab == 0 ? LIME : CARD));
        decodeTab.setBackground(shape(tab == 1 ? PURPLE : CARD));
    }
    private void begin(String message) { busy = true; status.setText(message); progress.setVisibility(View.VISIBLE); updateControls(); }
    private void finish(String message) { busy = false; status.setText(message); progress.setVisibility(View.GONE); updateControls(); }
    private void updateControls() {
        for (View view : controls) view.setEnabled(!busy);
        if (saveImage != null) saveImage.setEnabled(!busy && currentPngName != null);
        if (shareImage != null) shareImage.setEnabled(!busy && currentPngName != null);
        if (copyText != null) copyText.setEnabled(!busy && decodedText != null);
        if (saveText != null) saveText.setEnabled(!busy && decodedText != null);
    }
    private void failure(Exception error) {
        ui(() -> { finish("Operation failed"); error(error.getMessage() == null ? "File operation failed." : error.getMessage()); });
    }
    private void ui(Runnable action) { runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) action.run(); }); }
    private void error(String message) { new AlertDialog.Builder(this).setTitle("PixelText").setMessage(message).setPositiveButton("OK", null).show(); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

    private void writeDraft(String text) throws IOException {
        try (OutputStream stream = new FileOutputStream(new File(getFilesDir(), "draft.txt"))) {
            stream.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
    private void restoreDraft() {
        File draft = new File(getFilesDir(), "draft.txt");
        final String imageName = currentPngName;
        final File recovered = new File(getFilesDir(), "decoded.txt");
        if (!draft.exists() && !recovered.exists()) return;
        begin("Restoring draft…");
        worker.execute(() -> {
            try {
                String draftText = "";
                if (draft.exists()) {
                    try (InputStream stream = new FileInputStream(draft)) {
                        draftText = new String(PixelPng.readBounded(stream, TextImageCodec.MAX_TEXT_BYTES), StandardCharsets.UTF_8);
                    }
                }
                final String text = draftText;
                Bitmap restoredImage = null;
                if (imageName != null && imageName.matches("pixeltext-[0-9a-f-]{36}\\.png")) {
                    File png = new File(getCacheDir(), imageName);
                    if (png.exists()) {
                        try (InputStream saved = new FileInputStream(png)) {
                            restoredImage = PixelPng.read(saved);
                            if (!PixelPng.decode(restoredImage).text.equals(text)) {
                                restoredImage.recycle(); restoredImage = null;
                            }
                        }
                    }
                }
                final Bitmap preview = restoredImage;
                String savedText = null;
                if (recovered.exists()) {
                    try (InputStream saved = new FileInputStream(recovered)) {
                        savedText = new String(PixelPng.readBounded(saved, TextImageCodec.MAX_TEXT_BYTES), StandardCharsets.UTF_8);
                    }
                }
                final String recoveredText = savedText;
                ui(() -> {
                    restoring = true; input.setText(text); restoring = false;
                    currentPngName = preview == null ? null : imageName;
                    if (preview != null) {
                        encodePreview.setImageBitmap(preview); encodePreview.setVisibility(View.VISIBLE);
                        encodeInfo.setText(R.string.restored_png);
                    }
                    if (recoveredText != null) {
                        decodedText = recoveredText;
                        output.setText(recoveredText.length() > 100000 ? recoveredText.substring(0,100000) + "\n\n[Save .txt for the complete text.]" : recoveredText.isEmpty() ? "[Empty text]" : recoveredText);
                        decodeInfo.setText(R.string.restored_text);
                    }
                    finish("Draft restored");
                });
            } catch (Exception error) { failure(error); }
        });
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("tab", selectedTab); state.putInt("mode", mode.getSelectedItemPosition());
        state.putString("png", currentPngName);
        // Text stays out of Binder bundles, which have a small transaction limit.
        try { writeDraft(input.getText().toString()); } catch (IOException ignored) { /* Next explicit encode retries. */ }
        super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    private LinearLayout column(int padding) {
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(padding), dp(padding), dp(padding), dp(padding)); return layout;
    }
    private LinearLayout row() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.HORIZONTAL); return layout; }
    private LinearLayout card() {
        LinearLayout layout = column(18); layout.setBackground(shape(CARD));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = dp(14);
        layout.setLayoutParams(params); return layout;
    }
    private GradientDrawable shape(int color) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(16)); return drawable;
    }
    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(color);
        view.setLineSpacing(dp(3), 1f); view.setPadding(0, dp(5), 0, dp(5)); return view;
    }
    private Button button(String text, boolean primary, View.OnClickListener action) {
        Button view = new Button(this); view.setText(text); view.setAllCaps(false); view.setTextSize(14);
        view.setTextColor(primary ? BG : FG); view.setBackground(shape(primary ? LIME : Color.rgb(38,44,59)));
        view.setMinHeight(dp(48)); view.setPadding(dp(12), dp(8), dp(12), dp(8));
        view.setOnClickListener(action); controls.add(view);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(12); view.setLayoutParams(params);
        return view;
    }
    private void addHalf(LinearLayout row, View view) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.topMargin = dp(12); params.rightMargin = dp(5); row.addView(view, params);
    }
    private ImageView preview() {
        ImageView view = new ImageView(this); view.setAdjustViewBounds(true); view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setMaxHeight(dp(300)); view.setContentDescription("512 by 512 image containing compressed text");
        view.setVisibility(View.GONE); LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(12); params.bottomMargin = dp(12); view.setLayoutParams(params); return view;
    }
}
