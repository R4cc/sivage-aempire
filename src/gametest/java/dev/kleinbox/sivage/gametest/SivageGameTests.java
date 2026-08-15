package dev.kleinbox.sivage.gametest;

import com.mojang.datafixers.util.Pair;
import dev.kleinbox.sivage.item.ImageItem;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.lang.reflect.Method;

public final class SivageGameTests implements CustomTestMethodInvoker {
    @GameTest
    public void itemFramesRequireSupportAndClearSpace(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos support = helper.absolutePos(new BlockPos(0, 1, 0));
        BlockPos framePosition = support.relative(Direction.EAST);
        level.setBlockAndUpdate(support, Blocks.STONE.defaultBlockState());

        ItemFrame frame = new ItemFrame(level, framePosition, Direction.EAST);
        helper.assertTrue(frame.survives(), "A frame on solid support should survive");
        helper.assertTrue(level.addFreshEntity(frame), "The supported frame should be inserted");

        ItemFrame overlapping = new ItemFrame(level, framePosition, Direction.EAST);
        helper.assertFalse(overlapping.survives(), "A frame must not overlap an existing frame");

        frame.discard();
        level.setBlockAndUpdate(support, Blocks.AIR.defaultBlockState());
        ItemFrame unsupported = new ItemFrame(level, framePosition, Direction.EAST);
        helper.assertFalse(unsupported.survives(), "A frame without support must not survive");
        helper.succeed();
    }

    @GameTest
    @SuppressWarnings("removal")
    public void imageDamageUsesResponsiblePlayer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        ServerPlayer other = helper.makeMockServerPlayerInLevel();
        ItemFrame frame = new ItemFrame(level, helper.absolutePos(BlockPos.ZERO), Direction.NORTH);
        frame.setAttached(ImageItem.SIGNATURE_TYPE, Pair.of(owner.getStringUUID(), "https://example.com/image.png"));

        helper.assertTrue(
                ImageItem.canDamageImage(frame, level.damageSources().playerAttack(owner)),
                "The image owner should be able to damage the image"
        );
        helper.assertFalse(
                ImageItem.canDamageImage(frame, level.damageSources().playerAttack(other)),
                "A non-owner should not be able to damage the image"
        );

        Snowball ownerProjectile = new Snowball(level, owner, new ItemStack(Items.SNOWBALL));
        Snowball otherProjectile = new Snowball(level, other, new ItemStack(Items.SNOWBALL));
        helper.assertTrue(
                ImageItem.canDamageImage(frame, level.damageSources().thrown(ownerProjectile, null)),
                "Projectile damage should inherit the owner's authorization"
        );
        helper.assertFalse(
                ImageItem.canDamageImage(frame, level.damageSources().thrown(otherProjectile, null)),
                "A non-owner projectile should not bypass image authorization"
        );
        helper.assertTrue(
                ImageItem.canDamageImage(frame, level.damageSources().generic()),
                "Unowned environmental damage should continue to break images"
        );
        helper.succeed();
    }

    @Override
    public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
        method.invoke(this, helper);
    }
}
