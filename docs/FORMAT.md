# PixelText image format v1

## Pixel storage

The image is exactly 512×512 pixels. All pixels have alpha 255. Read rows left to right, top to bottom; each pixel contributes its red, green, then blue byte. The first 32 bytes form the header. Exactly the declared number of payload bytes follows. Remaining pixels are unused padding, currently opaque `#161A25`; padding is excluded from checksums. The last occupied pixel can contain one or two zero-filled trailing channels outside the payload.

The original PNG is the transport. JPEG compression, filtering, palette quantization, color adjustment, resampling, or screen capture can alter encoded bytes. The Android PNG reader requests unscaled mutable ARGB_8888 in sRGB. Checksums must pass before recovered text is accepted.

## Header

All integers are big-endian. The implementation stores 32-bit CRC values as Java signed ints; comparison uses the complete 32-bit pattern.

| Offset | Size | Meaning |
| --- | ---: | --- |
| 0 | 8 | ASCII magic `PXTEXT01` |
| 8 | 1 | Version: `1` |
| 9 | 1 | Codec: `0` raw UTF-8, `1` adaptive arithmetic, `2` zlib DEFLATE |
| 10 | 2 | Reserved flags: `0` |
| 12 | 4 | Original UTF-8 byte length, 0–8,388,608 |
| 16 | 4 | Payload byte length, 0–786,400 |
| 20 | 4 | CRC32 of original UTF-8 bytes |
| 24 | 4 | CRC32 of compressed/raw payload |
| 28 | 4 | CRC32 of header bytes 0–27 |

Length bounds, format fields, and checksums are validated. Raw payload length must equal original length. DEFLATE must finish at exactly the declared output length with no trailing compressed bytes. Output that exceeds the declared size is rejected. Recovered text must be valid UTF-8; invalid Unicode input is rejected rather than silently replaced.

CRC32 is an accidental corruption check, not cryptographic authentication. The format provides neither encryption nor error correction. Unused padding is not authenticated.

## Adaptive arithmetic model

The model contains 257 possible contexts: one for each preceding byte 0–255 and a separate start-of-stream context 256. Each context has 256 symbol frequencies, initially one. Contexts are allocated lazily. A context's probability estimate is `frequency(symbol) / total(context)`.

For each input byte, narrow a 32-bit arithmetic interval to that symbol's cumulative probability range, using ascending byte order. Emit bits when the interval lies wholly in the lower or upper half; defer underflow bits when it lies in the middle half. After coding the symbol, increment its frequency and use that byte as the next context.

At context total 16,384, rescale each count to `(count + 1) / 2`, rounded down with integer division. Every count remains at least one. A Fenwick tree computes cumulative frequencies and finds decoded symbols in logarithmic time. Encoder and decoder update the same model in the same order, so no frequency table or external model is transmitted.

Arithmetic coding uses inclusive `low=0`, `high=2^32−1`. Interval multiplication uses 64-bit integers. Renormalization thresholds are `2^31`, `2^30`, and `3×2^30`. Finish with the conventional disambiguating bit and deferred complements, followed by 32 explicit zero bits and zero padding to the next byte boundary. The decoder reads a 32-bit initial code and decodes exactly the header's original length. Truncated look-ahead is rejected; the original-data CRC validates the decoded result.

## Selection and limits

Automatic mode chooses the smallest fitting payload among raw UTF-8, adaptive arithmetic, and zlib DEFLATE level 9. Equal lengths retain the first candidate: raw, then arithmetic, then DEFLATE. Candidates that exceed the image capacity are discarded during compression; if no candidate fits, encoding fails with a capacity error. Explicit modes keep their selected compressor and fail if it does not fit.

The original text limit is 8 MiB. The Android PNG reader limits file input to 4 MiB and checks dimensions before bitmap allocation. Import is deliberately not a camera scanner or a generic steganographic photo decoder.
