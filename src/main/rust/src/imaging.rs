use crate::palette::Palette;
use image::imageops::colorops::brighten_in_place;
use image::imageops::{ColorMap, FilterType, crop_imm, dither, fast_blur, index_colors, resize};
use image::{
    DynamicImage, ImageBuffer, ImageDecoder, ImageError, ImageReader, Limits, Rgba, RgbaImage,
    SubImage, imageops,
};
use std::io::Cursor;

static BG_BLUR_FACTOR: f32 = 24f32;
static BG_DARKEN_FACTOR: i32 = -45;
static BG_ZOOM_FACTOR: f32 = 1.32;
const MAX_SOURCE_DIMENSION: u32 = 8_192;
const MAX_SOURCE_PIXELS: u64 = 16_777_216;
const MAX_DECODE_ALLOCATION: u64 = 128 * 1024 * 1024;

#[derive(Debug, PartialEq, Eq)]
pub enum ImagingError {
    InvalidImage,
    TooLarge,
}

pub struct Metadata {
    pub width: u32,
    pub height: u32,
    pub stretch: bool,
    pub transparent: bool,
    pub dithering: bool,
    pub nearest_neighbor: bool,
}

pub fn get_map_art(raw: &[u8], metadata: &Metadata) -> Result<Vec<u8>, ImagingError> {
    let mut reader = ImageReader::new(Cursor::new(raw))
        .with_guessed_format()
        .map_err(|_| ImagingError::InvalidImage)?;
    let mut limits = Limits::default();
    limits.max_image_width = Some(MAX_SOURCE_DIMENSION);
    limits.max_image_height = Some(MAX_SOURCE_DIMENSION);
    limits.max_alloc = Some(MAX_DECODE_ALLOCATION);
    reader.limits(limits);

    let decoder = reader.into_decoder().map_err(classify_decode_error)?;
    let (source_width, source_height) = decoder.dimensions();
    let source_pixels = u64::from(source_width)
        .checked_mul(u64::from(source_height))
        .ok_or(ImagingError::TooLarge)?;
    if source_pixels > MAX_SOURCE_PIXELS {
        return Err(ImagingError::TooLarge);
    }
    let img = DynamicImage::from_decoder(decoder).map_err(classify_decode_error)?;
    let frame = if metadata.transparent { 0 } else { 128 / 16 };
    let wasted_space = frame * 2;

    // Apply components (Whole canvas, actual image and blurred background)

    let mut canvas = RgbaImage::from_pixel(metadata.width, metadata.height, Rgba([0, 0, 0, 0]));
    let inner = minimize_image(metadata, img, frame);

    let (off_x, off_y) = determine_offset(frame, &canvas, &inner);

    if !metadata.transparent {
        let zoomed_bg = zoomed_view(&inner, BG_ZOOM_FACTOR).to_image();
        let mut blurred = fast_blur(
            &resize(
                &zoomed_bg,
                canvas.width() - wasted_space,
                canvas.height() - wasted_space,
                FilterType::Triangle,
            ),
            BG_BLUR_FACTOR,
        );
        brighten_in_place(&mut blurred, BG_DARKEN_FACTOR);
        imageops::overlay(&mut canvas, &blurred, frame as i64, frame as i64);
    }
    imageops::overlay(&mut canvas, &inner, off_x as i64, off_y as i64);

    // Apply color palette

    let palette = Palette::new();
    if metadata.dithering {
        dither(&mut canvas, &palette);
    } else {
        index_colors(&canvas, &palette);
    }

    // To map array

    let (w, h) = (canvas.width() as usize, canvas.height() as usize);
    let mut map = vec![0u8; w * h];

    for (i, pixel) in canvas.pixels().enumerate() {
        let value = palette.index_of(pixel) as u8;
        map[i] = value;
    }

    Ok(map)
}

fn classify_decode_error(error: ImageError) -> ImagingError {
    if matches!(error, ImageError::Limits(_)) {
        ImagingError::TooLarge
    } else {
        ImagingError::InvalidImage
    }
}

fn zoomed_view(inner: &RgbaImage, zoom_factor: f32) -> SubImage<&RgbaImage> {
    let (width, height) = inner.dimensions();

    let crop_width = (width as f32 / zoom_factor).round() as u32;
    let crop_height = (height as f32 / zoom_factor).round() as u32;

    let crop_x = (width - crop_width) / 2;
    let crop_y = (height - crop_height) / 2;

    crop_imm(inner, crop_x, crop_y, crop_width, crop_height)
}

fn determine_offset(
    frame: u32,
    canvas: &ImageBuffer<Rgba<u8>, Vec<u8>>,
    inner: &RgbaImage,
) -> (u32, u32) {
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

    let filter = if metadata.nearest_neighbor {
        FilterType::Nearest
    } else {
        FilterType::Lanczos3
    };

    if metadata.stretch {
        img = DynamicImage::resize_exact(&img, width, height, filter);
    } else {
        img = DynamicImage::resize(&img, width, height, filter);
    }

    img.into_rgba8()
}

#[cfg(test)]
mod tests {
    use super::*;
    use image::ImageFormat;
    use std::io::Cursor;

    fn encode_png(width: u32, height: u32) -> Vec<u8> {
        let image = RgbaImage::from_fn(width, height, |x, y| {
            Rgba([(x % 256) as u8, (y % 256) as u8, 128, 255])
        });
        let mut cursor = Cursor::new(Vec::new());
        DynamicImage::ImageRgba8(image)
            .write_to(&mut cursor, ImageFormat::Png)
            .expect("PNG encoding should succeed");
        cursor.into_inner()
    }

    fn metadata() -> Metadata {
        Metadata {
            width: 128,
            height: 128,
            stretch: false,
            transparent: false,
            dithering: true,
            nearest_neighbor: true,
        }
    }

    #[test]
    fn renders_valid_image_deterministically() {
        let raw = encode_png(128, 128);
        for _ in 0..16 {
            let map = get_map_art(&raw, &metadata()).expect("valid PNG should render");
            assert_eq!(map.len(), 128 * 128);
        }
    }

    #[test]
    fn rejects_invalid_image() {
        assert_eq!(
            get_map_art(b"not an image", &metadata()),
            Err(ImagingError::InvalidImage)
        );
    }

    #[test]
    fn rejects_excessive_dimensions_before_decode() {
        let raw = encode_png(MAX_SOURCE_DIMENSION + 1, 1);
        assert_eq!(get_map_art(&raw, &metadata()), Err(ImagingError::TooLarge));
    }
}
