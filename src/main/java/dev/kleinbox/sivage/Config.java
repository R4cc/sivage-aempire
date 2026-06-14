package dev.kleinbox.sivage;

import folk.sisby.kaleido.api.WrappedConfig;
import folk.sisby.kaleido.lib.quiltconfig.api.annotations.Alias;
import folk.sisby.kaleido.lib.quiltconfig.api.annotations.Comment;
import folk.sisby.kaleido.lib.quiltconfig.api.annotations.IntegerRange;
import folk.sisby.kaleido.lib.quiltconfig.api.values.ValueList;

import java.util.List;

public class Config extends WrappedConfig {

    @Comment("Controls how this mod interacts with the internet.")
    public final Network network = new Network();
    public static class Network implements Section {

        @Comment("List of specifically allowed domains or wildcards. Overrules the blacklist.")
        public List<String> whitelist = ValueList.create("");

        @Comment("List of specifically disallowed domains or wildcards. Overruled by the whitelist.")
        public List<String> blacklist = ValueList.create("",
                "localhost"
        );

        @Comment("Specifies the maximum size in bytes that images are allowed to have while downloading. (0 means infinite)")
        @IntegerRange(min = 0, max = Integer.MAX_VALUE)
        @Alias("file_size_limit")
        public int fileSizeLimit = 500_000_000; // 0.5 GB
    }

    @Comment("This represents aspects of this mod that only have an affect within the game.")
    public final Game game = new Game();
    public static class Game implements Section {

        @Comment("When enabled, each player may have only one image generated at a time.")
        @Alias("player_limit")
        public boolean playerLimit = false;

        @Comment("Maximum width and height, in blocks, for newly created images.")
        @IntegerRange(min = 1, max = 8)
        @Alias("max_size")
        public int maxSize = 4;

        @Comment("When enabled, newly created images will use invisible item frames.")
        @Alias("invisible_frames")
        public boolean invisibleFrames = false;

        @Comment("Maximum amount of placed images each player may own at a time. Set to 0 to disable this limit.")
        @IntegerRange(min = 0, max = Integer.MAX_VALUE)
        @Alias("max_images_per_player")
        public int maxImagesPerPlayer = 16;
    }
}
