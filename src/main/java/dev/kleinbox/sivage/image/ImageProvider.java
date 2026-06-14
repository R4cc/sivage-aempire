package dev.kleinbox.sivage.image;

import dev.kleinbox.sivage.Sivage;
import dev.kleinbox.sivage.item.ImageMetaData;
import dev.kleinbox.sivage.packet.ImageDialogs;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class ImageProvider {
    /**
     * <p>Downloads from the specified url and attempts to return a map, if the response is valid.</p>
     *
     * <p>Note that this is blocking the current thread.</p>
     * @return An image or nothing, if it could not be downloaded.
     * @throws ImagePreparationException Whenever it fails to download the image.
     */
    public static byte[] getRawImage(MinecraftServer server, URL url) throws ImagePreparationException {
        HttpURLConnection connection = null;
        int maxBytes = Sivage.CONFIG.network.fileSizeLimit;

        try {
            connection = (HttpURLConnection) url.openConnection(server.getProxy());
            connection.setDoInput(true);
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.connect();

            if (connection.getResponseCode() / 100 != 2)
                throw new ImagePreparationException(ImageDialogs.FAILED_LINK);

            int contentLength = connection.getContentLength();
            if (contentLength > maxBytes && maxBytes != 0 && contentLength != -1)
                throw new ImagePreparationException(ImageDialogs.TOO_LARGE);

            try (InputStream in = connection.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream(
                         Math.max(8192, Math.min(contentLength, maxBytes))
                 )) {

                byte[] buffer = new byte[8192];
                int totalRead = 0;
                int read;

                while ((read = in.read(buffer)) != -1) {
                    totalRead += read;

                    if (totalRead > maxBytes && maxBytes != 0)
                        throw new ImagePreparationException(ImageDialogs.TOO_LARGE);

                    out.write(buffer, 0, read);
                }

                return out.toByteArray();
            }

        } catch (IOException e) {
            throw new ImagePreparationException(ImageDialogs.FAILED_LINK);
        } finally {
            if (connection != null)
                connection.disconnect();
        }
    }

    /**
     * <p>Takes a raw image and converts it to a map with the aspects specified in metadata.</p>
     *
     * @return A 2D map of in-game maps with the map images stored in level.
     * @throws ImagePreparationException Whenever it cannot parse the image.
     */
    public static ItemStack[][] createMaps(ServerLevel level, byte[] raw, ImageMetaData metadata) throws ImagePreparationException {
        ServerLevel savedLevel = getMapStoreLevel(level.getServer());

        byte[] image = new Imaging().getMapArt(raw, metadata);

        int width = metadata.getWidth(), height = metadata.getHeight();
        int mapsW = (int) Math.ceil(width / 128D);
        int mapsH = (int) Math.ceil(height / 128D);

        ItemStack[][] maps = new ItemStack[mapsH][mapsW];

        for (int mapY=0; mapY<mapsH; mapY++) {
            for (int mapX=0; mapX<mapsW; mapX++) {
                MapId id = savedLevel.getFreeMapId();
                MapItemSavedData map = new MapItemSavedData(
                        0, 0, (byte) 1,
                        false, false, true,
                        savedLevel.dimension()
                );

                int startX = mapX * 128;
                int startY = mapY * 128;

                for (int y=0; y<128; y++)
                    for (int x=0; x<128; x++)
                        map.setColor(x, y, image[(startY+y) * width + (startX+x)]);

                savedLevel.setMapData(id, map);

                ItemStack itemStack = new ItemStack(Items.FILLED_MAP);
                itemStack.set(DataComponents.MAP_ID, id);

                maps[mapY][mapX] = itemStack;
            }
        }

        return maps;
    }

    /**
     * <p>Used to determine which dimension a map should refer to.</p>
     *
     * <p>Since we use a shared cache no matter from which dimension a map is coming from, we should ensure
     * the maps always refer to one dimension.</p>
     *
     * <p>In theory any dimension could be used. In our case, the END will always be returned in order to not
     * flood the map IDs on the overworld and nether with our images.</p>
     */
    private static ServerLevel getMapStoreLevel(MinecraftServer server) {
        return server.getLevel(Level.END);
    }
}
