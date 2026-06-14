use image::{imageops, load_from_memory, DynamicImage, ImageBuffer, Rgba, RgbaImage, SubImage};
use image::imageops::{fast_blur, dither, FilterType, ColorMap, index_colors, resize, crop_imm, CatmullRom};
use image::imageops::colorops::brighten_in_place;
use crate::palette::Palette;

static BG_BLUR_FACTOR: f32 = 24f32;
static BG_DARKEN_FACTOR: i32 = -45;
static BG_ZOOM_FACTOR: f32 = 1.32;

pub struct Metadata {
    pub width: u32,
    pub height: u32,
    pub stretch: bool,
    pub transparent: bool,
    pub dithering: bool,
    pub nearest_neighbor: bool,
}

pub fn get_map_art(raw: &[u8], metadata: &Metadata) -> Option<Vec<u8>> {
    let img = load_from_memory(raw)
        .ok()?;
    let frame = if metadata.transparent { 0 } else { 128 / 16 };
    let wasted_space = frame * 2;

    // Apply components (Whole canvas, actual image and blurred background)

    let mut canvas = RgbaImage::from_pixel(metadata.width, metadata.height, Rgba([0,0,0,0]));
    let inner = minimize_image(metadata, img, frame);

    let (off_x, off_y) = determine_offset(frame, &canvas, &inner);

    if !metadata.transparent {
        let zoomed_bg = zoomed_view(&inner, BG_ZOOM_FACTOR).to_image();
        let mut blurred = fast_blur(&resize(&zoomed_bg, canvas.width() - wasted_space, canvas.height() - wasted_space, FilterType::Triangle), BG_BLUR_FACTOR);
        brighten_in_place(&mut blurred, BG_DARKEN_FACTOR);
        imageops::overlay(&mut canvas, &blurred, frame as i64, frame as i64);
    }
    imageops::overlay(&mut canvas, &inner, off_x as i64, off_y as i64);

    // Apply color palette

    let palette = Palette::new();
    if metadata.dithering {
        dither(&mut canvas, &palette);
    } else {
        index_colors(&mut canvas, &palette);
    }

    // To map array

    let (w, h) = (canvas.width() as usize, canvas.height() as usize);
    let mut map = vec![0u8; w * h];

    for (i, pixel) in canvas.pixels().enumerate() {
        let value = palette.index_of(pixel) as u8;
        map[i] = value;
    }

    Some(map)
}

fn zoomed_view(inner: &RgbaImage, zoom_factor: f32) -> SubImage<&RgbaImage> {
    let (width, height) = inner.dimensions();

    let crop_width = (width as f32 / zoom_factor).round() as u32;
    let crop_height = (height as f32 / zoom_factor).round() as u32;

    let crop_x = ((width - crop_width) / 2).max(0);
    let crop_y = ((height - crop_height) / 2).max(0);

    crop_imm(inner, crop_x, crop_y, crop_width, crop_height)
}

fn determine_offset(frame: u32, canvas: &ImageBuffer<Rgba<u8>, Vec<u8>>, inner: &RgbaImage) -> (u32, u32) {
    let wasted_space = frame * 2;

    let has_gaps = inner.width() != canvas.width() || inner.height() != canvas.height();
    let (mut off_x, mut off_y) = (frame, frame);

    if has_gaps {
        let img_width = canvas.width() - wasted_space;
        let img_height = canvas.height() - wasted_space;

        off_x += img_width / 2 - inner.width() / 2;
        off_y += img_height / 2 - inner.height() / 2;
    }
    (off_x, off_y)
}

fn minimize_image(metadata: &Metadata, mut img: DynamicImage, frame: u32) -> RgbaImage {
    let wasted_space = frame * 2;

    let width = metadata.width - wasted_space;
    let height = metadata.height - wasted_space;

    let filter = if metadata.nearest_neighbor { FilterType::Nearest } else { FilterType::Lanczos3 };

    if metadata.stretch {
        img = DynamicImage::resize_exact(&img, width, height, filter);
    } else {
        img = DynamicImage::resize(&img, width, height, filter);
    }

    img.into_rgba8()
}

#[cfg(test)]
mod tests {
    use std::fs::File;
    use std::io::Read;
    use std::thread::sleep;
    use std::time::Duration;
    use image::{RgbaImage};
    use tempfile::tempdir;
    use super::*;

    #[test]
    fn test_get_map_art() {
        let dir = tempdir().expect("Failed to create temp dir");
        let before_path = dir.path().join("before.png");
        let after_path = dir.path().join("after.png");

        // Prepare input image

        let (width, height) = (128, 128);

        let mut img: ImageBuffer<Rgba<u8>, Vec<u8>> =
            ImageBuffer::new(width, height);

        for y in 0..height {
            for x in 0..width {
                let r = (x as f32 / width as f32 * 255.0) as u8;
                let g = (y as f32 / height as f32 * 255.0) as u8;
                let b = 128;
                img.put_pixel(x, y, Rgba([r, g, b, 255]));
            }
        }

        img.save(&before_path).expect("Failed to save input image");

        // Output

        let metadata = Metadata {
            width: 64,
            height: 64,
            stretch: false,
            transparent: false,
            dithering: true,
            nearest_neighbor: true
        };

        let mut file = File::open(&before_path).expect("Failed to open before.png");
        let mut buf = Vec::new();
        file.read_to_end(&mut buf).expect("Failed to read before.png");

        let output = get_map_art(&buf, &metadata);
        assert!(output.is_some(), "get_map_art was unable to read the format");

        let map = output.unwrap();
        let palette = Palette::new();

        let mut img = RgbaImage::new(metadata.width, metadata.height);

        for y in 0..metadata.height {
            for x in 0..metadata.width {
                let idx = map[(y * metadata.width + x) as usize];
                img.put_pixel(x, y, palette.lookup(idx as usize).unwrap_or(Rgba::from([0, 0, 0, 0])));
            }
        }

        img.save(&after_path).expect("Failed to save output image");

        opener::open(dir.path()).unwrap_or_else(|_| println!("Saved images at {:?}", dir));
        sleep(Duration::from_secs_f32(5f32));
    }
}