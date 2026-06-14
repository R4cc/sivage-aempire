package dev.kleinbox.sivage.image;

import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasm.WasmModule;
import dev.kleinbox.sivage.item.ImageMetaData;
import dev.kleinbox.sivage.packet.ImageDialogs;

/**
 * <p>Interface to image parsing written in rust and running via WASM.</p>
 * 
 * <p>Changes made in this class must be carefully aligned with the rust code.</p>
 */
public class Imaging {
    private final Instance instance;

    public Imaging() {
        WasmModule module = RawImaging.load();
        instance = Instance.builder(module)
                .withMachineFactory(RawImagingMachine::new)
                .withStart(false)
                .build();
    }

    private long getMapArt(int ptr, int len, ImageMetaData metadata) {
        ExportFunction func = instance.export("get_map_art");
        return  func.apply(ptr, len, metadata.getWidth(), metadata.getHeight(), metadata.stretch() ? 1L : 0L, metadata.transparent() ? 1L : 0L, metadata.dithering() ? 1L : 0L, metadata.nearestNeighbor() ? 1L : 0L)[0];
    }

    public byte[] getMapArt(byte[] imageBytes, ImageMetaData metadata) throws ImagePreparationException  {
        try {
            int inPtr = alloc(imageBytes.length);
            write(inPtr, imageBytes);

            long packed = getMapArt(inPtr, imageBytes.length, metadata);
            if (packed == 0L)
                throw new ImagePreparationException(ImageDialogs.FAILED_FILE);

            int outPtr = (int) (packed >>> 32);
            int outLen = (int) packed;

            byte[] map = read(outPtr, outLen);

            free(outPtr, outLen);
            free(inPtr, imageBytes.length);

            return map;
        } catch (Throwable e) {
            throw new ImagePreparationException(ImageDialogs.FAILED_FILE);
        }
    }

    private int alloc(int length) {
        ExportFunction func = instance.export("alloc");
        return (int) func.apply(length)[0];
    }

    private void free(int pointer, int length) {
        ExportFunction func = instance.export("free");
        func.apply(pointer, length);
    }

    private void write(int pointer, byte[] data) {
        instance.memory().write(pointer, data, 0, data.length);
    }

    private byte[] read(int pointer, int length) {
        return instance.memory().readBytes(pointer, length);
    }
}
