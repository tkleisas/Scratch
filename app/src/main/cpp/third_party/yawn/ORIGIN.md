# YAWN DSP provenance

These files are vendored from the YAWN project:

https://github.com/tkleisas/yawn

YAWN is MIT licensed. The full license text is preserved in `LICENSE.txt`.
The vendored headers had two minimal compatibility edits: references to
YAWN's `kDefaultSampleRate` constant were replaced with `48000.0` so the
files compile without adopting YAWN's core headers.
