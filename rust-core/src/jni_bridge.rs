//! JNI surface. Kotlin side: `com.precord.app.core.NativeBridge` (an `object`).
//! Status convention: `0` = ok, negative = failure (never throws across the FFI boundary).

use crate::engine::Engine;
use crate::wav;
use jni::objects::{JFloatArray, JLongArray, JObject, JShortArray, JString};
use jni::sys::{jboolean, jfloat, jint, jlong, JNI_TRUE};
use jni::JNIEnv;
use std::path::PathBuf;

fn engine<'a>(h: jlong) -> Option<&'a Engine> {
    if h == 0 {
        None
    } else {
        // SAFETY: handle comes from `nativeCreate` and Kotlin guarantees it is not
        // used after `nativeDestroy` (capture thread is joined first).
        Some(unsafe { &*(h as *const Engine) })
    }
}

fn path_of(env: &mut JNIEnv, s: &JString) -> Option<PathBuf> {
    env.get_string(s).ok().map(|j| PathBuf::from(String::from(j)))
}

#[no_mangle]
pub extern "system" fn Java_com_precord_app_core_NativeBridge_nativeCreate(
    _env: JNIEnv,
    _this: JObject,
    sample_rate: jint,
    window_ms: jlong,
) -> jlong {
    match Engine::new(sample_rate.max(0) as u32, window_ms.max(0) as u64) {
        Some(e) => Box::into_raw(Box::new(e)) as jlong,
        None => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_precord_app_core_NativeBridge_nativeDestroy(_env: JNIEnv, _this: JObject, handle: jlong) {
    if handle != 0 {
        // SAFETY: created by Box::into_raw in nativeCreate; called exactly once.
        drop(unsafe { Box::from_raw(handle as *mut Engine) });
    }
}

#[no_mangle]
pub extern "system" fn Java_com_precord_app_core_NativeBridge_nativeWrite(
    env: JNIEnv,
    _this: JObject,
    handle: jlong,
    pcm: JShortArray,
    len: jint,
) {
    let Some(e) = engine(handle) else { return };
    let mut scratch = [0i16; 2048];
    let mut off = 0i32;
    while off < len {
        let n = (len - off).min(scratch.len() as i32) as usize;
        if env.get_short_array_region(&pcm, off, &mut scratch[..n]).is_err() {
            return;
        }
        e.process(&scratch[..n]);
        off += n as i32;
    }
}

#[no_mangle]
pub extern "system" fn Java_com_precord_app_core_NativeBridge_nativeSetDsp(
    _env: JNIEnv,
    _this: JObject,
    handle: jlong,
    gain_db: jfloat,
    gate_enabled: jboolean,
    gate_db: jfloat,
    highpass: jboolean,
    voice: jboolean,
) {
    if let Some(e) = engine(handle) {
        e.set_dsp(gain_db, gate_enabled == JNI_TRUE, gate_db, highpass == JNI_TRUE, voice == JNI_TRUE);
    }
}

/// out[0] = rms, out[1] = peak (linear 0..1)
#[no_mangle]
pub extern "system" fn Java_com_precord_app_core_NativeBridge_nativeLevels(
    env: JNIEnv,
    _this: JObject,
    handle: jlong,
    out: JFloatArray,
) {
    if let Some(e) = engine(handle) {
        let (rms, peak) = e.levels();
        let _ = env.set_float_array_region(&out, 0, &[rms, peak]);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_precord_app_core_NativeBridge_nativeScope(
    env: JNIEnv,
    _this: JObject,
    handle: jlong,
    out: JFloatArray,
) {
    if let Some(e) = engine(handle) {
        // Fixed stack buffer: no heap allocation on the 25 Hz UI path.
        let mut v = [0f32; crate::engine::SCOPE_LEN];
        let n = (env.get_array_length(&out).unwrap_or(0).max(0) as usize).min(v.len());
        e.scope(&mut v[..n]);
        let _ = env.set_float_array_region(&out, 0, &v[..n]);
    }
}

/// out[0] = buffered samples, out[1] = window samples
#[no_mangle]
pub extern "system" fn Java_com_precord_app_core_NativeBridge_nativeBufferInfo(
    env: JNIEnv,
    _this: JObject,
    handle: jlong,
    out: JLongArray,
) {
    if let Some(e) = engine(handle) {
        let _ = env.set_long_array_region(&out, 0, &[e.buffered_samples() as i64, e.window_samples() as i64]);
    }
}

/// out: [samples_written, duration_ms]
#[no_mangle]
pub extern "system" fn Java_com_precord_app_core_NativeBridge_nativeSaveWav(
    mut env: JNIEnv,
    _this: JObject,
    handle: jlong,
    path: JString,
    last_ms: jlong,
    normalize: jboolean,
    out: JLongArray,
) -> jint {
    let (Some(e), Some(p)) = (engine(handle), path_of(&mut env, &path)) else { return -1 };
    match e.save_wav(&p, last_ms.max(0) as u64, normalize == JNI_TRUE) {
        Ok(s) => {
            let _ = env.set_long_array_region(&out, 0, &[s.count as i64, s.duration_ms() as i64]);
            0
        }
        Err(_) => -2,
    }
}

/// out: [start_abs, count, gain_as_f32_bits]
#[no_mangle]
pub extern "system" fn Java_com_precord_app_core_NativeBridge_nativePlan(
    env: JNIEnv,
    _this: JObject,
    handle: jlong,
    last_ms: jlong,
    normalize: jboolean,
    out: JLongArray,
) -> jint {
    let Some(e) = engine(handle) else { return -1 };
    match e.plan(last_ms.max(0) as u64, normalize == JNI_TRUE) {
        Ok(s) => {
            let _ = env.set_long_array_region(&out, 0, &[s.start as i64, s.count as i64, s.gain.to_bits() as i64]);
            0
        }
        Err(_) => -2,
    }
}

/// Returns number of samples copied into `dst`, or -1 when the data was overwritten.
#[no_mangle]
pub extern "system" fn Java_com_precord_app_core_NativeBridge_nativeReadPcm(
    env: JNIEnv,
    _this: JObject,
    handle: jlong,
    start: jlong,
    dst: JShortArray,
    count: jint,
    gain_bits: jint,
) -> jint {
    let Some(e) = engine(handle) else { return -1 };
    // Callers use 4096-sample chunks; cap at the stack buffer instead of allocating per call.
    let mut v = [0i16; 8192];
    let n = (count.max(0) as usize).min(v.len());
    if !e.read_pcm(start as u64, &mut v[..n], f32::from_bits(gain_bits as u32)) {
        return -1;
    }
    match env.set_short_array_region(&dst, 0, &v[..n]) {
        Ok(()) if n == count.max(0) as usize => n as jint,
        _ => -1,
    }
}

/// out: [sample_rate, frames]
#[no_mangle]
pub extern "system" fn Java_com_precord_app_core_NativeBridge_nativeWavInfo(
    mut env: JNIEnv,
    _this: JObject,
    path: JString,
    out: JLongArray,
) -> jint {
    let Some(p) = path_of(&mut env, &path) else { return -1 };
    match wav::info_of(&p) {
        Ok(i) => {
            let _ = env.set_long_array_region(&out, 0, &[i.sample_rate as i64, i.frames as i64]);
            0
        }
        Err(_) => -2,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_precord_app_core_NativeBridge_nativeWavPeaks(
    mut env: JNIEnv,
    _this: JObject,
    path: JString,
    out: JFloatArray,
) -> jint {
    let Some(p) = path_of(&mut env, &path) else { return -1 };
    let n = env.get_array_length(&out).unwrap_or(0).max(1) as usize;
    match wav::peaks(&p, n) {
        Ok(v) => {
            let _ = env.set_float_array_region(&out, 0, &v);
            0
        }
        Err(_) => -2,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_precord_app_core_NativeBridge_nativeTrimWav(
    mut env: JNIEnv,
    _this: JObject,
    src: JString,
    dst: JString,
    start_ms: jlong,
    end_ms: jlong,
) -> jint {
    let (Some(s), Some(d)) = (path_of(&mut env, &src), path_of(&mut env, &dst)) else { return -1 };
    match wav::trim(&s, &d, start_ms.max(0) as u64, end_ms.max(0) as u64) {
        Ok(_) => 0,
        Err(_) => -2,
    }
}
