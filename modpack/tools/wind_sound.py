"""Generates assets/zoneraids/sounds/ambient_wind.ogg, the seamless wind loop of the Zone ambience.

Noise shaped in the frequency domain is periodic, so the loop has no seam: a low rumble and a higher
whistle, each under its own slow gust envelope. Usage: python wind_sound.py out.wav, then
ffmpeg -i out.wav -c:a libvorbis -q:a 2 src/main/resources/assets/zoneraids/sounds/ambient_wind.ogg
"""
import numpy as np, wave, sys
sr = 22050; secs = 24; n = sr * secs
rng = np.random.default_rng(7)
f = np.fft.rfftfreq(n, 1 / sr)
def band(lo, hi, tilt):
    spec = (rng.normal(size=f.size) + 1j * rng.normal(size=f.size))
    shape = np.exp(-((np.log(np.maximum(f, 1)) - np.log((lo * hi) ** 0.5)) ** 2) / (2 * (np.log(hi / lo) / 2) ** 2))
    spec *= shape / np.maximum(f, 20) ** tilt
    x = np.fft.irfft(spec, n)
    return x / np.max(np.abs(x))
t = np.arange(n) / n  # 0..1 over the loop
def env(cycles, phases):
    e = np.zeros(n)
    for c, p, w in zip(cycles, phases, [1, 0.6, 0.35, 0.2]):
        e += w * np.sin(2 * np.pi * c * t + p)
    e = (e - e.min()) / (e.max() - e.min())
    return e
low = band(60, 400, 0.5) * (0.45 + 0.55 * env([1, 2, 5, 9], [0.3, 1.1, 2.0, 0.7]))
high = band(500, 1600, 0.3) * (0.05 + 0.95 * env([2, 3, 7, 11], [1.7, 0.2, 2.9, 1.3]) ** 2.5)
x = 0.8 * low + 0.25 * high
x = x / np.max(np.abs(x)) * 0.85
pcm = (x * 32767).astype('<i2')
with wave.open(sys.argv[1], 'wb') as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr); w.writeframes(pcm.tobytes())
