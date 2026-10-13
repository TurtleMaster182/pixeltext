# Changes

## 1.0.1

Opening a text file saved as UTF-16 or an older encoding previously failed with the raw message `Input length = 1`. Text-file import now recognizes UTF-8 and UTF-16 byte-order marks and offers an explicit encoding selection when a file is not valid UTF-8. CRLF, line breaks, blank lines, diacritics and emoji are preserved. Malformed files receive a readable error rather than a low-level decoder message.

Added file-import regression tests and an Android emulator job that checks native PNG round trips and multiline paste through the real editor. The PNG format remains compatible with version 1.0.0.
