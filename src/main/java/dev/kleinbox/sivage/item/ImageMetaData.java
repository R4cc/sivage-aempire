package dev.kleinbox.sivage.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.kleinbox.sivage.Sivage;

public record ImageMetaData(String url, float width, float height, boolean stretch, boolean transparent, boolean dithering, boolean nearestNeighbor) {
    public static final Codec<ImageMetaData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
                Codec.STRING.fieldOf("url").forGetter(ImageMetaData::url),
                Codec.FLOAT.fieldOf("width").forGetter(ImageMetaData::width),
                Codec.FLOAT.fieldOf("height").forGetter(ImageMetaData::height),
                Codec.BOOL.fieldOf("stretch").forGetter(ImageMetaData::stretch),
                Codec.BOOL.optionalFieldOf("transparent", false).forGetter(ImageMetaData::transparent),
                Codec.BOOL.fieldOf("dithering").forGetter(ImageMetaData::dithering),
                Codec.BOOL.fieldOf("nearestNeighbor").forGetter(ImageMetaData::nearestNeighbor)
        ).apply(instance, ImageMetaData::new)
    );

    public int getWidth() {
        return getBlockWidth() * 128;
    }

    public int getHeight() {
        return getBlockHeight() * 128;
    }

    public int getBlockWidth() {
        return clampBlockSize(width());
    }

    public int getBlockHeight() {
        return clampBlockSize(height());
    }

    private static int clampBlockSize(float size) {
        int maxSize = Sivage.CONFIG.game.maxSize;
        int blockSize = (int) size;
        return Math.clamp(blockSize, 1, maxSize);
    }
}
