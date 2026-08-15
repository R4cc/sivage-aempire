package dev.kleinbox.sivage.mixin;

import dev.kleinbox.sivage.item.ImageItem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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

    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void usePaintingDamageBehavior(ServerLevel level, DamageSource damageSource, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (!this.hasAttached(ImageItem.ID_TYPE))
            return;

        ItemFrame frame = (ItemFrame) (Object) this;
        if (!ImageItem.canDamageImage(frame, damageSource)) {
            cir.setReturnValue(false);
            return;
        }
        cir.setReturnValue(super.hurtServer(level, damageSource, amount));
    }

    @Inject(method = "dropItem(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void dropCustomImage(ServerLevel level, Entity breaker, CallbackInfo ci) {
        if (!this.hasAttached(ImageItem.ID_TYPE))
            return;

        ImageItem.destroyImage(level, (ItemFrame) (Object) this, breaker);
        ci.cancel();
    }
}
