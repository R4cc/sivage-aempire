package dev.kleinbox.sivage.image;

import dev.kleinbox.sivage.packet.ImageDialogs;

public class ImagePreparationException extends RuntimeException {
    private final ImageDialogs dialog;

    public ImagePreparationException(ImageDialogs dialog) {
        super(dialog.name());
        this.dialog = dialog;
    }

    public ImageDialogs getDialog() {
        return dialog;
    }
}
