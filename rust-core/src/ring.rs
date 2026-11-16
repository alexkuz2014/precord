//! Lock-free single-producer circular buffer of 16-bit PCM samples.
//!
//! Design
//! ------
//! * Physical capacity = `window + slack`. Only `window` samples are ever
//!   exposed as "buffered", the `slack` region is head-room that lets a reader
//!   copy the *oldest* retained sample while the producer keeps running.
//! * Positions are absolute, monotonically increasing sample counters, so
//!   wrap-around is handled purely with `abs % capacity`.
//! * The producer is wait-free. Readers use a seqlock: the producer publishes
//!   `claimed` (how far it is *about* to write) before touching data, readers
//!   copy and then verify `claimed` did not pass the region they read.
//! * Storage is zero-allocated (`calloc` semantics) so untouched pages cost no
//!   RAM: a 60 minute buffer only consumes memory as it actually fills up.
//! * All slot accesses go through `AtomicI16` (relaxed), so there is no data
//!   race (and no UB) even when a reader overlaps the producer.

use std::alloc::{alloc_zeroed, dealloc, Layout};
use std::sync::atomic::{fence, AtomicI16, AtomicU64, Ordering};

pub struct RingBuffer {
    ptr: *mut i16,
    cap: usize,
    window: usize,
    max_block: usize,
    /// Samples fully written and published (Release).
    write_pos: AtomicU64,
    /// Samples the producer has started writing (>= write_pos).
    claimed: AtomicU64,
}

// SAFETY: every access to `ptr` goes through atomics; positions are atomics.
unsafe impl Send for RingBuffer {}
unsafe impl Sync for RingBuffer {}

impl RingBuffer {
    /// `window` = number of samples that can be saved; `slack` = extra head-room.
    /// Returns `None` if the allocation fails (e.g. low-memory device).
    pub fn new(window: usize, slack: usize) -> Option<Self> {
        if window == 0 {
            return None;
        }
        let slack = slack.max(4);
        let cap = window.checked_add(slack)?;
        let layout = Layout::array::<i16>(cap).ok()?;
        // SAFETY: layout has non-zero size.
        let ptr = unsafe { alloc_zeroed(layout) } as *mut i16;
        if ptr.is_null() {
            return None;
        }
        Some(Self {
            ptr,
            cap,
            window,
            max_block: slack / 2,
            write_pos: AtomicU64::new(0),
            claimed: AtomicU64::new(0),
        })
    }

    #[inline]
    fn slot(&self, idx: usize) -> &AtomicI16 {
        debug_assert!(idx < self.cap);
        // SAFETY: idx < cap, pointer is valid & aligned for the buffer lifetime.
        unsafe { AtomicI16::from_ptr(self.ptr.add(idx)) }
    }

    pub fn window(&self) -> usize {
        self.window
    }

    pub fn capacity(&self) -> usize {
        self.cap
    }

    /// Total samples ever written.
    pub fn total_written(&self) -> u64 {
        self.write_pos.load(Ordering::Acquire)
    }

    /// Samples currently available for saving (<= window).
    pub fn buffered(&self) -> usize {
        (self.total_written().min(self.window as u64)) as usize
    }

    /// Producer side. Must only be called from one thread at a time.
    pub fn write(&self, mut data: &[i16]) {
        while !data.is_empty() {
            let n = data.len().min(self.max_block);
            let (head, rest) = data.split_at(n);
            let w = self.write_pos.load(Ordering::Relaxed);
            self.claimed.store(w + n as u64, Ordering::Relaxed);
            fence(Ordering::Release);
            let mut idx = (w % self.cap as u64) as usize;
            for &s in head {
                self.slot(idx).store(s, Ordering::Relaxed);
                idx += 1;
                if idx == self.cap {
                    idx = 0;
                }
            }
            self.write_pos.store(w + n as u64, Ordering::Release);
            data = rest;
        }
    }

    /// `(start_abs, count)` of the newest `n` samples (clamped to what is buffered).
    pub fn tail(&self, n: usize) -> (u64, usize) {
        let w = self.total_written();
        let count = (n as u64).min(w).min(self.window as u64) as usize;
        (w - count as u64, count)
    }

    /// Copy `out.len()` samples starting at absolute position `start`.
    /// Returns `false` if the range is not yet written or was (possibly)
    /// overwritten while copying; `out` content is then unspecified.
    pub fn read_range(&self, start: u64, out: &mut [i16]) -> bool {
        let end = start + out.len() as u64;
        let limit = start + self.cap as u64;
        if end > self.write_pos.load(Ordering::Acquire) || self.claimed.load(Ordering::Relaxed) > limit {
            return false;
        }
        let mut idx = (start % self.cap as u64) as usize;
        for o in out.iter_mut() {
            *o = self.slot(idx).load(Ordering::Relaxed);
            idx += 1;
            if idx == self.cap {
                idx = 0;
            }
        }
        fence(Ordering::Acquire);
        self.claimed.load(Ordering::Relaxed) <= limit
    }
}

impl Drop for RingBuffer {
    fn drop(&mut self) {
        // SAFETY: same layout as in `new`.
        unsafe { dealloc(self.ptr as *mut u8, Layout::array::<i16>(self.cap).unwrap()) }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    fn dump(rb: &RingBuffer, n: usize) -> Vec<i16> {
        let (start, count) = rb.tail(n);
        let mut v = vec![0i16; count];
        assert!(rb.read_range(start, &mut v));
        v
    }

    #[test]
    fn empty_buffer() {
        let rb = RingBuffer::new(100, 20).unwrap();
        assert_eq!(rb.buffered(), 0);
        assert_eq!(rb.tail(50), (0, 0));
        assert!(!rb.read_range(0, &mut [0i16; 1]));
    }

    #[test]
    fn fills_without_wrap() {
        let rb = RingBuffer::new(100, 20).unwrap();
        let data: Vec<i16> = (0..60).collect();
        rb.write(&data);
        assert_eq!(rb.buffered(), 60);
        assert_eq!(dump(&rb, 1000), data);
        assert_eq!(dump(&rb, 10), (50..60).collect::<Vec<i16>>());
    }

    #[test]
    fn wraps_around_and_keeps_newest_window() {
        let rb = RingBuffer::new(100, 20).unwrap(); // cap 120
        for chunk in (0..1000i16).collect::<Vec<_>>().chunks(37) {
            rb.write(chunk);
        }
        assert_eq!(rb.total_written(), 1000);
        assert_eq!(rb.buffered(), 100);
        assert_eq!(dump(&rb, 5000), (900..1000).collect::<Vec<i16>>());
        assert_eq!(dump(&rb, 3), vec![997, 998, 999]);
    }

    #[test]
    fn single_write_larger_than_capacity() {
        let rb = RingBuffer::new(50, 10).unwrap();
        let data: Vec<i16> = (0..500).map(|x| x as i16).collect();
        rb.write(&data);
        assert_eq!(dump(&rb, 50), (450..500).collect::<Vec<i16>>());
    }

    #[test]
    fn overwritten_range_is_rejected() {
        let rb = RingBuffer::new(100, 20).unwrap();
        rb.write(&vec![1i16; 100]);
        let (start, count) = rb.tail(100);
        assert_eq!((start, count), (0, 100));
        rb.write(&vec![2i16; 10]); // still inside slack
        let mut v = vec![0i16; 100];
        assert!(rb.read_range(start, &mut v));
        rb.write(&vec![2i16; 40]); // clobbers the oldest samples
        assert!(!rb.read_range(start, &mut v));
    }

    #[test]
    fn concurrent_reader_never_sees_torn_data() {
        // Producer writes a monotonically increasing counter (wrapping i16);
        // every successful snapshot must therefore be strictly consecutive.
        let rb = Arc::new(RingBuffer::new(4096, 2048).unwrap());
        let p = rb.clone();
        let producer = std::thread::spawn(move || {
            let mut counter: u32 = 0;
            let mut block = [0i16; 256];
            for _ in 0..20_000 {
                for b in block.iter_mut() {
                    *b = counter as i16;
                    counter = counter.wrapping_add(1);
                }
                p.write(&block);
                if counter % (256 * 16) == 0 {
                    std::thread::yield_now(); // behave like a paced audio thread
                    std::thread::sleep(std::time::Duration::from_micros(50));
                }
            }
        });
        let mut ok = 0;
        let mut rejected = 0;
        let mut buf = vec![0i16; 4096];
        while !producer.is_finished() {
            let (start, count) = rb.tail(4096);
            if count < 4096 {
                continue;
            }
            if rb.read_range(start, &mut buf) {
                ok += 1;
                for w in buf.windows(2) {
                    assert_eq!(w[1], w[0].wrapping_add(1), "torn snapshot");
                }
            } else {
                rejected += 1;
            }
        }
        producer.join().unwrap();
        assert!(ok > 0, "no snapshot succeeded (rejected {rejected})");
    }
}
