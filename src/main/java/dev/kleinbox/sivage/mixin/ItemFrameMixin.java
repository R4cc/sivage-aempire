package dev.kleinbox.sivage.mixin;

import dev.kleinbox.sivage.item.ImageItem;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemFrame.class)
public abstract class ItemFrameMixin extends HangingEntity {

    protected ItemFrameMixin(EntityType<? extends @NotNull HangingEntity> entityType, Level level) {
        super(entityType, level);
    }

    @SuppressWarnings("UnstableApiUsage")
    @Inject(method = "playPlacementSound", at = @At("HEAD"), cancellable = true)
    public void playPlacementSound(CallbackInfo ci) {
        if (this.hasAttached(ImageItem.ID_TYPE))
            ci.cancel();
    }
}
