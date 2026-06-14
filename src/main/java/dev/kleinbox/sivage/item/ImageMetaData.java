package dev.kleinbox.sivage.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public record ImageMetaData(String url, float width, float height, boolean stretch, boolean transparent, boolean dithering, boolean nearestNeighbor) {
    public static final Codec<ImageMetaData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
                Codec.STRING.fieldOf("url").forGetter(ImageMetaData::url),
                Codec.FLOAT.fieldOf("width").forGetter(ImageMetaData::width),
                Codec.FLOAT.fieldOf("height").forGetter(ImageMetaData::height),
                Codec.BOOL.fieldOf("stretch").forGetter(ImageMetaData::stretch),
                Codec.BOOL.fieldOf("transparent").forGetter(ImageMetaData::transparent),
                Codec.BOOL.fieldOf("dithering").forGetter(ImageMetaData::dithering),
                Codec.BOOL.fieldOf("nearestNeighbor").forGetter(ImageMetaData::nearestNeighbor)
        ).apply(instance, ImageMetaData::new)
    );

    public int getWidth() {
        int width = (int) width();
        return Math.min(width, ImageItem.MAX_SIZE) * 128;
    }

    public int getHeight() {
        int height = (int) height();
        return Math.min(height, ImageItem.MAX_SIZE) * 128;
    }
}
