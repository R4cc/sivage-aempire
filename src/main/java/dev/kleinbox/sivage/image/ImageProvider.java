package dev.kleinbox.sivage.image;

import dev.kleinbox.sivage.Sivage;
import dev.kleinbox.sivage.item.ImageMetaData;
import dev.kleinbox.sivage.packet.ImageDialogs;
import dev.kleinbox.sivage.packet.LinkVerifier;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class ImageProvider {
    private static final int MAX_REDIRECTS = 5;
    private static final long SLOW_DOWNLOAD_MILLIS = 15_000L;
    private static final long TOTAL_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(60);

    /**
     * <p>Downloads from the specified url and attempts to return a map, if the response is valid.</p>
     *
     * <p>Note that this is blocking the current thread.</p>
     * @return An image or nothing, if it could not be downloaded.
     * @throws ImagePreparationException Whenever it fails to download the image.
     */
    public static byte[] getRawImage(MinecraftServer server, URL url) throws ImagePreparationException {
        return getRawImage(server, url, "unknown player");
    }

    public static byte[] getRawImage(MinecraftServer server, URL url, String requester) throws ImagePreparationException {
        return getRawImage(server.getProxy(), url, requester);
    }

    public static byte[] getRawImage(Proxy proxy, URL url, String requester) throws ImagePreparationException {
        int maxBytes = Sivage.CONFIG.network.effectiveFileSizeLimit();

        long startedAt = System.nanoTime();
        long downloadedBytes = 0L;
        URL current = url;
        boolean succeeded = false;

        try {
            PinnedDns pinnedDns = new PinnedDns();
            OkHttpClient client = clientFor(proxy, pinnedDns);
            for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
                // Resolve once per hostname and make OkHttp use precisely that validated address set.
                pinnedDns.pin(current.getHost());
                Request request = new Request.Builder().url(current.toString()).get().build();
                long remainingNanos = TOTAL_TIMEOUT_NANOS - (System.nanoTime() - startedAt);
                if (remainingNanos <= 0)
                    throw new ImagePreparationException(ImageDialogs.FAILED_LINK);
                Call call = client.newCall(request);
                call.timeout().timeout(remainingNanos, TimeUnit.NANOSECONDS);
                try (Response response = call.execute()) {
                    if (isRedirect(response.code())) {
                        if (redirects == MAX_REDIRECTS)
                            throw new ImagePreparationException(ImageDialogs.FAILED_LINK);
                        String location = response.header("Location");
                        if (location == null)
                            throw new ImagePreparationException(ImageDialogs.FAILED_LINK);

                        URI redirected = current.toURI().resolve(location);
                        if (current.getProtocol().equalsIgnoreCase("https")
                                && !"https".equalsIgnoreCase(redirected.getScheme()))
                            throw new ImagePreparationException(ImageDialogs.FAILED_LINK);
                        current = LinkVerifier.fromURI(redirected);
                        continue;
                    }

                    if (!response.isSuccessful())
                        throw new ImagePreparationException(ImageDialogs.FAILED_LINK);

                    ResponseBody body = response.body();
                    long contentLength = body.contentLength();
                    if (contentLength > maxBytes)
                        throw new ImagePreparationException(ImageDialogs.TOO_LARGE);

                    int initialSize = contentLength < 0
                            ? 8_192
                            : (int) Math.max(8_192L, Math.min(contentLength, maxBytes));
                    try (InputStream in = body.byteStream();
                         ByteArrayOutputStream out = new ByteArrayOutputStream(initialSize)) {
                        byte[] buffer = new byte[8_192];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            downloadedBytes += read;
                            if (downloadedBytes > maxBytes)
                                throw new ImagePreparationException(ImageDialogs.TOO_LARGE);
                            out.write(buffer, 0, read);
                        }

                        succeeded = true;
                        return out.toByteArray();
                    }
                }
            }
            throw new ImagePreparationException(ImageDialogs.FAILED_LINK);
        } catch (ImagePreparationException e) {
            throw e;
        } catch (Exception e) {
            throw new ImagePreparationException(ImageDialogs.FAILED_LINK);
        } finally {
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            if (elapsedMillis >= SLOW_DOWNLOAD_MILLIS) {
                Sivage.LOGGER.warn(
                        "Image download from {} for {} took {} ms ({} bytes, {}).",
                        safeUrl(current), requester, elapsedMillis, downloadedBytes, succeeded ? "completed" : "failed"
                );
            }
        }
    }

    private static OkHttpClient clientFor(Proxy proxy, Dns dns) {
        return new OkHttpClient.Builder()
                .proxy(proxy)
                .dns(dns)
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    private static final class PinnedDns implements Dns {
        private final Map<String, List<InetAddress>> resolved = new HashMap<>();

        private synchronized List<InetAddress> pin(String hostname) throws UnknownHostException {
            return lookup(hostname);
        }

        @Override
        public synchronized List<InetAddress> lookup(String hostname) throws UnknownHostException {
            String key = hostname.toLowerCase(Locale.ROOT);
            List<InetAddress> addresses = resolved.get(key);
            if (addresses == null) {
                addresses = List.copyOf(LinkVerifier.resolvePublicHost(hostname));
                resolved.put(key, addresses);
            }
            return addresses;
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static String safeUrl(URL url) {
        int port = url.getPort();
        return url.getProtocol() + "://" + url.getHost() + (port == -1 ? "" : ":" + port) + url.getPath();
    }

    /**
     * <p>Takes a raw image and converts it to a map with the aspects specified in metadata.</p>
     *
     * @return A 2D map of in-game maps with the map images stored in level.
     * @throws ImagePreparationException Whenever it cannot parse the image.
     */
    public static byte[] renderMapColors(byte[] raw, ImageMetaData metadata) throws ImagePreparationException {
        return new Imaging().getMapArt(raw, metadata);
    }

    public static ItemStack[][] createMaps(ServerLevel level, byte[] image, ImageMetaData metadata) {
        ServerLevel savedLevel = getMapStoreLevel(level.getServer());

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
        ServerLevel end = server.getLevel(Level.END);
        return end == null ? server.overworld() : end;
    }
}
