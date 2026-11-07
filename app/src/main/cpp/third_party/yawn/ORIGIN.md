# YAWN DSP provenance

These files are vendored from the YAWN project:

https://github.com/tkleisas/yawn

YAWN is MIT licensed. The full license text is preserved in `LICENSE.txt`.

Vendored DSP:

- `audio/TimeStretcher.*`, `audio/TransientDetector.*`
- `effects/Biquad.h`
- `effects/Filter.*`, `effects/FreqMap.h`
- `effects/Bitcrusher.h`, `audio/Oversampling.h`
- `effects/Delay.*`
- `effects/HallReverb.*` — Freeverb-style room/hall reverb based on the public-domain Freeverb design by Jezar at Dreampoint.
- `effects/TapeEmulation.*`
- `effects/Compressor.*`
- `effects/Limiter.*`

The effect sources are unmodified apart from include paths. Scratch provides
a minimal local `effects/AudioEffect.h` plus `core/ParameterInfo.h` and
`core/WidgetHint.h` so the effects compile without YAWN's DAW framework,
JSON preset state, or UI widget code.

Two earlier compatibility edits remain: references to YAWN's
`kDefaultSampleRate` constant were replaced with `48000.0` so the files
compile without adopting YAWN's core headers.
