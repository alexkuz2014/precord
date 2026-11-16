# User guide

Precord keeps the last few minutes of microphone audio in memory. When something worth keeping happens, save it - the audio from *before* you pressed the button is included.

## First start
1. Open **Deck**, tap **Arm buffer**. Allow *Microphone* (and *Notifications* on Android 13+).
2. A persistent notification shows `03:42 / 05:00` buffered. Recording is only ever kept in RAM until you save.

## Deck
* **Scope & meter** - live level of what the microphone hears.
* **Buffer ring** - how full the circular buffer is.
* **Buffer length** - 30s, 1m, 5m, 15m, 30m, 60m or *Custom* (drag the dial; it ticks per step). The line under the pills shows exact RAM use. Changing length or sample rate restarts the buffer.
* **SAVE REPLAY** - writes everything buffered to a file; capture continues.
* **Save last 30s / 1m / 5m** - save only the newest part.

## Saving without opening the app
* **Notification** - *SAVE REPLAY* button (works on the lock screen). *STOP* disarms.
* **Quick Settings tile** - add *Precord* from the tile editor; tap to arm/disarm.
* **Shake to save** (Settings → Triggers) - three hard shakes. Keeps the phone's CPU awake while armed (battery cost).
* **Volume Up + Down** (GitHub APK only) - enable the *Precord volume combo* accessibility service; it sees only those two keys.

## Library
Search by title/tag. Open a clip to rename it, add tags, **tap the waveform to seek**, **drag the two handles to trim**, play the selection, then *Trim > new copy* or *Trim > replace*. **Share** opens the system share sheet; **Export** copies to `Recordings/Precord`.

## Settings
* **Sample rate** 16 / 44.1 / 48 kHz - lower = less RAM (16 kHz: 115 MB per hour).
* **Format** WAV (lossless), M4A (AAC), Opus (16/48 kHz only).
* **Normalize on save** - raises quiet recordings to -1 dBFS (max +30 dB).
* **Raw microphone** - turns off phone auto-gain/noise suppression where supported.
* **Input gain**, **Noise gate**, **Auto-export**.

## Tips & limits
* 60 min @ 48 kHz needs ~345 MB RAM; prefer 16 kHz for hour-long voice buffers.
* Android may stop the app on very low memory; re-arm afterwards. The buffer is lost when you disarm or the phone restarts.
* Another app using the microphone (calls) may interrupt capture.
* Only record where the law and the people involved allow it.
