//! Precord core: the audio engine behind the Android app.
//!
//! * [`ring`]   – single-producer, lock-free circular PCM buffer with
//!                seqlock-validated snapshots (readers never block the audio thread).
//! * [`engine`] – gain / noise-gate DSP, metering, scope, snapshot planning,
//!                peak normalisation and streaming WAV export.
//! * [`wav`]    – WAV header parsing, waveform peaks and lossless trimming.
//! * `jni_bridge` – the `extern "system"` surface used by Kotlin.

pub mod aec;
pub mod denoise;
pub mod engine;
pub mod ring;
pub mod wav;

mod jni_bridge;
