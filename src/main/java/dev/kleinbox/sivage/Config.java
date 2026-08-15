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
        public static final int MAX_FILE_SIZE_LIMIT = 50_000_000;

        @Comment("List of specifically allowed domains or wildcards. Overrules the blacklist.")
        public List<String> whitelist = ValueList.create("");

        @Comment("List of specifically disallowed domains or wildcards. Overruled by the whitelist.")
        public List<String> blacklist = ValueList.create("",
                "localhost"
        );

        @Comment("Maximum image download size in bytes. Values outside 1..50000000 are capped at 50000000.")
        @IntegerRange(min = 0, max = Integer.MAX_VALUE)
        @Alias("file_size_limit")
        public int fileSizeLimit = MAX_FILE_SIZE_LIMIT;

        @Comment("Maximum number of image downloads/conversions processed concurrently across the server.")
        @IntegerRange(min = 1, max = 16)
        @Alias("max_concurrent_requests")
        public int maxConcurrentRequests = 4;

        public int effectiveFileSizeLimit() {
            return fileSizeLimit <= 0 || fileSizeLimit > MAX_FILE_SIZE_LIMIT
                    ? MAX_FILE_SIZE_LIMIT
                    : fileSizeLimit;
        }
    }

    @Comment("This represents aspects of this mod that only have an affect within the game.")
    public final Game game = new Game();
    public static class Game implements Section {

        @Comment("Deprecated: one active image request per player is now always enforced for server safety.")
        @Alias("player_limit")
        public boolean playerLimit = true;

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
