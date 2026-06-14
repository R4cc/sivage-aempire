mod imaging;
mod palette;

use std::{mem, slice};
use std::mem::ManuallyDrop;
use crate::imaging::Metadata;

#[unsafe(no_mangle)]
pub extern "C" fn alloc(len: u32) -> u32 {
    let mut v = Vec::<u8>::with_capacity(len as usize);
    let ptr = v.as_mut_ptr() as u32;
    mem::forget(v);
    ptr
}

#[unsafe(no_mangle)]
pub extern "C" fn free(ptr: u32, len: u32) {
    unsafe {
        let _ = Vec::from_raw_parts(ptr as *mut u8, len as usize, len as usize);
    }
}

/// Makes a call to `imaging::get_map_art`. The data at `ptr` must be the raw image and match
/// with the given `len`.
///
/// ## Returns
///
/// Will return a packed u64 containing a pointer to memory with the length of the allocated space.
/// This method will allocate memory for the output. However, it is up to the host handling the
/// allocated input and output for this method.
///
/// ## Safety
///
/// `ptr` and `len` must point to a valid and pre-allocated part of the memory.
/// Otherwise, undefined behaviour will occur.
#[unsafe(no_mangle)]
pub extern "C" fn get_map_art(ptr: u32, len: u32, width: u32, height: u32, stretch: bool,
                              transparent: bool, dithering: bool, nearest_neighbor: bool) -> u64 {
    let raw = unsafe { slice::from_raw_parts(ptr as *const u8, len as usize) };
    let metadata = Metadata { width, height, stretch, transparent, dithering, nearest_neighbor };

    // Make actual call

    let map = imaging::get_map_art(raw, &metadata);
    if map.is_none() { return 0; };
    let map = map.unwrap();

    // Provide output via pointer

    let mut out = ManuallyDrop::new(map);

    let out_ptr = out.as_mut_ptr() as u32;
    let out_len = out.len() as u32;

    ((out_ptr as u64) << 32) | out_len as u64
}