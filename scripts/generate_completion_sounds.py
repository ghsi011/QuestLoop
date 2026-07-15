#!/usr/bin/env python3
"""Synthesizes the quest-completion chimes bundled in app/src/main/res/raw.

Pure-stdlib additive bell synthesis (sine partials + exponential decay), so the
assets are reproducible from source instead of being opaque binaries:

    python3 scripts/generate_completion_sounds.py

Five cues, matching core's CompletionChime enum (CompletionSoundCues.kt):
  chime_progress  - one soft note: a partial progress log
  chime_minor     - two-note bling: trivial/easy quest done
  chime_major     - three-note rise: medium/hard quest done
  chime_triumph   - four-note arpeggio + sparkle: epic quest / achievement
  chime_level_up  - arpeggio into a closing chord: level-up fanfare

Relative loudness across cues is baked to be similar; the app scales playback
volume by the completion's earned XP (CompletionSound.volume).
"""

import math
import struct
import wave
from pathlib import Path

SAMPLE_RATE = 44100
PEAK = 0.72  # normalization target; headroom against clipping on chords

# Note frequencies (Hz), equal temperament.
A4 = 440.00
C5 = 523.25
E5 = 659.25
G5 = 783.99
A5 = 880.00
C6 = 1046.50
E6 = 1318.51
G6 = 1567.98


def bell(freq, duration, amplitude=1.0):
    """One bell-like note: harmonic partials, fast attack, exponential decay."""
    # (harmonic multiple, relative level); slight inharmonic top adds shimmer.
    partials = [(1.0, 1.0), (2.0, 0.40), (3.0, 0.15), (4.16, 0.06)]
    n = int(duration * SAMPLE_RATE)
    attack = int(0.004 * SAMPLE_RATE)
    tau = duration / 3.5
    samples = [0.0] * n
    for mult, level in partials:
        f = freq * mult
        if f > SAMPLE_RATE / 2:
            continue
        # A hair of detune per partial keeps the tone from sounding sterile.
        detune = 1.0 + 0.0015 * mult
        for i in range(n):
            t = i / SAMPLE_RATE
            env = math.exp(-t / tau)
            if i < attack:
                env *= i / attack
            samples[i] += level * env * math.sin(2 * math.pi * f * detune * t)
    top = max(abs(s) for s in samples) or 1.0
    return [s / top * amplitude for s in samples]


def mix(notes, total):
    """Mixes (offset_seconds, samples) pairs into one normalized buffer."""
    out = [0.0] * int(total * SAMPLE_RATE)
    for offset, samples in notes:
        start = int(offset * SAMPLE_RATE)
        for i, s in enumerate(samples):
            j = start + i
            if j < len(out):
                out[j] += s
    top = max(abs(s) for s in out) or 1.0
    out = [s / top * PEAK for s in out]
    # Short fade-out so the file never ends on a click.
    fade = min(int(0.02 * SAMPLE_RATE), len(out))
    for k in range(fade):
        out[len(out) - fade + k] *= 1 - k / fade
    return out


def write_wav(path, samples):
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SAMPLE_RATE)
        w.writeframes(
            b"".join(struct.pack("<h", int(max(-1.0, min(1.0, s)) * 32767)) for s in samples)
        )
    print(f"wrote {path} ({path.stat().st_size} bytes)")


def main():
    raw = Path(__file__).resolve().parent.parent / "app/src/main/res/raw"

    write_wav(raw / "chime_progress.wav", mix(
        [(0.00, bell(A5, 0.30, 0.9))],
        total=0.35,
    ))

    write_wav(raw / "chime_minor.wav", mix(
        [(0.00, bell(E5, 0.25, 0.8)),
         (0.09, bell(A5, 0.35, 1.0))],
        total=0.50,
    ))

    write_wav(raw / "chime_major.wav", mix(
        [(0.00, bell(A4, 0.28, 0.8)),
         (0.10, bell(E5, 0.28, 0.9)),
         (0.20, bell(A5, 0.50, 1.0))],
        total=0.75,
    ))

    write_wav(raw / "chime_triumph.wav", mix(
        [(0.00, bell(C5, 0.30, 0.8)),
         (0.09, bell(E5, 0.30, 0.85)),
         (0.18, bell(G5, 0.35, 0.9)),
         (0.27, bell(C6, 0.65, 1.0)),
         # Quiet high sparkle over the landing note.
         (0.31, bell(G6, 0.45, 0.25))],
        total=1.00,
    ))

    write_wav(raw / "chime_level_up.wav", mix(
        [(0.00, bell(C5, 0.25, 0.8)),
         (0.08, bell(E5, 0.25, 0.85)),
         (0.16, bell(G5, 0.25, 0.9)),
         (0.24, bell(C6, 0.35, 0.95)),
         # Closing major chord, the "ta-da".
         (0.42, bell(C6, 0.85, 0.9)),
         (0.42, bell(E6, 0.85, 0.7)),
         (0.42, bell(G6, 0.85, 0.55))],
        total=1.35,
    ))


if __name__ == "__main__":
    main()
