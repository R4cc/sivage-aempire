package dev.kleinbox.sivage.item;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.kleinbox.sivage.Sivage;
import dev.kleinbox.sivage.SivagePermissions;
import dev.kleinbox.sivage.image.ImagePreparationException;
import dev.kleinbox.sivage.image.ImageProvider;
import dev.kleinbox.sivage.packet.ImageDialogs;
import dev.kleinbox.sivage.packet.LinkVerifier;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.*;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URL;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <p>Represents the server side™ <i>Custom Image</i> item.</p>
 *
 * <p>A Custom Image consists of a vanilla Painting with the custom component <code>custom_image</code> set to true.</p>
 *
 * <h1>Usage</h1>
 * <p>Right-clicking this item on a block will open up a <code>sivage:new</code> dialog on the client, where the player
 * then can enter a link for a new image and configure its appearance.</p>
 *
 * <p>After hitting <i>"Generate"</i> in said dialog, a ClickEvent will be sent to the server, triggering <b>onUse</b>.</p>
 */
public class ImageItem {
    public static final Identifier PAYLOAD_ID = Sivage.of("new");
    public static final AttachmentType<@NotNull String> ID_TYPE = AttachmentRegistry.createPersistent(Sivage.of("image"), Codec.STRING);
    public static final AttachmentType<@NotNull Pair<String, String>> SIGNATURE_TYPE = AttachmentRegistry.createPersistent(Sivage.of("signed"), RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING.fieldOf("player").forGetter(Pair::getFirst),
                    Codec.STRING.fieldOf("url").forGetter(Pair::getSecond)
            ).apply(instance, Pair::new)
    ));
    public static final AttachmentType<@NotNull Long> CREATED_AT_TYPE = AttachmentRegistry.createPersistent(Sivage.of("created_at"), Codec.LONG);
    public static final String IS_CUSTOM_IMAGE_TYPE = "custom_image";
    public static final int MAX_SIZE = 4;
    public static final int MAX_IMAGES_PER_PLAYER = 16;
    private static final DateTimeFormatter CREATED_AT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault());

    private static final HashMap<UUID, CompletableFuture<Void>> RUNNING = new HashMap<>();
    private static final HashMap<UUID, Integer> PENDING_IMAGE_CREATIONS = new HashMap<>();

    public static final MutableComponent LIMIT_REACHED_MSG = Component.translatableWithFallback("sivage.hud.limit_reached", "Please wait for the previous image to finish generating.");
    public static final MutableComponent IMAGE_LIMIT_REACHED_MSG = Component.translatableWithFallback(
            "sivage.hud.image_limit_reached",
            "You can only have %s Sivage images placed at a time.",
            MAX_IMAGES_PER_PLAYER
    );

    public static boolean isStillGenerating(UUID uuid) {
        if (!Sivage.CONFIG.game.playerLimit) return false;

        @Nullable CompletableFuture<Void> task = RUNNING.get(uuid);
        if (task == null) return false;

        if (task.isDone()) RUNNING.remove(uuid);
        return !task.isDone();
    }

    /**
     * <p>Called whenever a player is right-clicking with an item on a block.</p>
     *
     * <p>This only opens the dialog on the client and nothing else.</p>
     *
     * @return <ul>
     *     <li>SUCCESS cancels further processing and, on the client, sends a packet to the server.
     *     <li>PASS falls back to further processing.
     *     <li>FAIL cancels further processing and does not send a packet to the server.
     * </ul>
     */
    public static InteractionResult onBlockUse(Player player, Level level, InteractionHand hand, BlockHitResult ignoredBlockHitResult) {
        @Nullable MinecraftServer server = level.getServer();
        if (server == null || level.isClientSide() || player.isSpectator())
            return InteractionResult.PASS;

        if (!isHoldingItem(player, hand))
            return InteractionResult.PASS;

        if (isStillGenerating(player.getUUID())) {
            player.sendOverlayMessage(LIMIT_REACHED_MSG);
            return InteractionResult.FAIL;
        }

        ServerPlayer serverPlayer = (ServerPlayer) player;
        if (!SivagePermissions.canCreate(serverPlayer)) {
            serverPlayer.sendSystemMessage(SivagePermissions.CREATE_DENIED);
            return InteractionResult.FAIL;
        }

        ImageDialogs.NEW.open((ServerLevel) level, serverPlayer);

        return InteractionResult.SUCCESS;
    }

    /**
     * <p>For (Glowing) ink sac interactions, allowing to make them (un)glowable. </p>
     */
    public static InteractionResult onEntityUse(Player player, Level level, InteractionHand interactionHand, Entity entity, @Nullable EntityHitResult ignoredEntityHitResult) {
        @Nullable MinecraftServer server = level.getServer();
        if (server == null || level.isClientSide() || player.isSpectator())
            return InteractionResult.PASS;
        ServerLevel serverLevel = (ServerLevel) level;

        if (!(entity instanceof ItemFrame frame) || !frame.hasAttached(ID_TYPE))
            return InteractionResult.PASS;

        ItemStack item = player.getItemInHand(interactionHand);
        Item itemKind = item.getItem();

        boolean glow;
        if (itemKind instanceof GlowInkSacItem && !(frame instanceof GlowItemFrame)) {
            glow = true;
        } else if (itemKind instanceof InkSacItem && (frame instanceof GlowItemFrame)) {
            glow = false;
        } else {
            if (player instanceof ServerPlayer serverPlayer) {
                sendImageInfo(serverPlayer, serverLevel, frame);
                return InteractionResult.SUCCESS;
            }

            return InteractionResult.FAIL;
        }

        ServerPlayer serverPlayer = (ServerPlayer) player;
        if (!SivagePermissions.canEdit(serverPlayer)) {
            serverPlayer.sendSystemMessage(SivagePermissions.EDIT_DENIED);
            return InteractionResult.FAIL;
        }

        if (!player.isCreative())
            item.consume(1, null);

        String uuid = frame.getAttachedOrThrow(ID_TYPE);
        Pair<String, String> signature = frame.getAttachedOrThrow(SIGNATURE_TYPE);
        @Nullable Long createdAt = frame.getAttached(CREATED_AT_TYPE);
        AtomicInteger size = new AtomicInteger();
        forWholeImage(serverLevel, frame, (framePart) -> {
            ItemStack subMap = framePart.getItem().copy();
            BlockPos pos = framePart.getPos();
            Direction facing = framePart.getDirection();
            boolean transparent = framePart.isInvisible();

            framePart.kill(serverLevel);

            ItemFrame newFramePart = createFramedImage(serverLevel, signature.getFirst(), signature.getSecond(), createdAt == null ? 0L : createdAt, pos, facing, subMap, uuid, transparent, glow);
            serverLevel.gameEvent(player, GameEvent.ENTITY_PLACE, newFramePart.position());
            serverLevel.addFreshEntity(newFramePart);

            size.getAndIncrement();
        });

        playSound(serverLevel, size.get(),  frame.getPos(), glow ? SoundEvents.GLOW_INK_SAC_USE : SoundEvents.INK_SAC_USE);

        return InteractionResult.SUCCESS;
    }

    /**
     * <p>Called after receiving the result of the dialog from the player.</p>
     *
     * <p>This will get the image, respond to the player's dialog and place the image.</p>
     *
     * @return true on success and false for pass/fail
     */
    public static boolean onPlace(Identifier id, Tag payload, ServerPlayer player) {
        // Parse received metadata from client

        DataResult<ImageMetaData> result = ImageMetaData.CODEC.parse(NbtOps.INSTANCE, payload);
        if (!(id.equals(PAYLOAD_ID) && result.isSuccess()))
            return false; // Not our payload

        ImageMetaData submittedMetadata = result.getOrThrow();
        ImageMetaData metadata = new ImageMetaData(
                submittedMetadata.url(),
                submittedMetadata.width(),
                submittedMetadata.height(),
                submittedMetadata.stretch(),
                false,
                submittedMetadata.dithering(),
                submittedMetadata.nearestNeighbor()
        );
        ServerLevel level = player.level();

        if (!SivagePermissions.canCreate(player)) {
            player.sendSystemMessage(SivagePermissions.CREATE_DENIED);
            ImageDialogs.close(player);
            return true;
        }

        if (isStillGenerating(player.getUUID())) {
            player.sendOverlayMessage(LIMIT_REACHED_MSG);
            return false; // Player is still generating something
        }

        // Get block for frame to be placed

        double reach = player.getAttributes().getValue(Attributes.BLOCK_INTERACTION_RANGE);
        HitResult hitResult = player.pick(reach, 0f, false);
        if (hitResult.getType() != HitResult.Type.BLOCK) {
            ImageDialogs.close(player); // Not looking at block
            return false;
        }
        BlockHitResult blockHitResult = (BlockHitResult) hitResult;

        Optional<Boolean> imageSlotReservation = reserveImageSlot(player);
        if (imageSlotReservation.isEmpty()) {
            player.sendOverlayMessage(IMAGE_LIMIT_REACHED_MSG);
            ImageDialogs.close(player);
            return false;
        }
        boolean imageSlotReserved = imageSlotReservation.get();

        // Consume item

        if (!player.isCreative()) {
            ItemStack consumed = ItemStack.EMPTY;

            for (InteractionHand hand : InteractionHand.values()) {
                if (isHoldingItem(player, hand)) {
                    ItemStack handItem = player.getItemInHand(hand);
                    consumed = handItem.copy();
                    consumed.setCount(1);

                    handItem.consume(1, null);
                    break;
                }
            }

            if (consumed.isEmpty()) {
                if (imageSlotReserved) releaseImageSlot(player.getUUID());
                ImageDialogs.close(player); // No item to consume
                return false;
            }
        }

        // Start preparing image

        AtomicBoolean placementScheduled = new AtomicBoolean(false);
        handleImageRequest(level, player, blockHitResult, () -> prepareImage(metadata, level, player, blockHitResult,
                (maps) -> {
                    placementScheduled.set(true);
                    level.getServer().execute(() -> {
                        try {
                            placeImage(player, level, blockHitResult, maps, metadata);
                        } finally {
                            if (imageSlotReserved) releaseImageSlot(player.getUUID());
                        }
                    });
                }), imageSlotReserved, placementScheduled);

        return true;
    }

    private static void handleImageRequest(ServerLevel level, ServerPlayer player, BlockHitResult blockHitResult, Runnable runnable, boolean imageSlotReserved, AtomicBoolean placementScheduled) {
        CompletableFuture<Void> task = CompletableFuture.runAsync(
                () -> {
                    try {
                        runnable.run();
                    } catch (ImagePreparationException e) {
                        if (!player.isCreative())
                            refund(level, blockHitResult);
                        e.getDialog().open(level, player);
                    } catch (Throwable e) {
                        if (!player.isCreative())
                            refund(level, blockHitResult);
                        Sivage.LOGGER.error("Image request for {} failed!", player.getPlainTextName(), e);
                        ImageDialogs.EXCEPTION.open(level, player);
                    } finally {
                        RUNNING.remove(player.getUUID());
                        if (imageSlotReserved && !placementScheduled.get()) {
                            releaseImageSlot(player.getUUID());
                        }
                    }
                },
                Util.backgroundExecutor()
        );

        if (Sivage.CONFIG.game.playerLimit)
            RUNNING.put(player.getUUID(), task);
    }

    private static void refund(ServerLevel level, BlockHitResult blockHitResult) {
        level.getServer().execute(() -> {
            Direction facing = blockHitResult.getDirection();
            Vec3 centeredPos = blockHitResult.getBlockPos().relative(facing).getCenter();

            ItemStack deposit = getDefaultConsumable(level);
            dropItemAt(level, deposit, centeredPos);
        });
    }

    /**
     * <p>Called when player attempts to destroy an entity (like our image within the world).</p>
     */
    public static InteractionResult onDestroy(Player player, Level level, InteractionHand ignoredInteractionHand, Entity entity, @Nullable EntityHitResult ignoredEntityHitResult) {
        if (level.isClientSide())
            return InteractionResult.PASS;

        if (!(entity instanceof ItemFrame frame && frame.hasAttached(ID_TYPE)))
            return InteractionResult.PASS;

        if (player instanceof ServerPlayer serverPlayer && !SivagePermissions.canRemove(serverPlayer)) {
            serverPlayer.sendSystemMessage(SivagePermissions.REMOVE_DENIED);
            return InteractionResult.FAIL;
        }

        destroyImage(player, (ServerLevel) level, frame);

        return InteractionResult.SUCCESS;
    }

    /**
     * <p>Responsible for downloading, adjusting, splitting and placing down the image with feedback to the player</p>
     */
    @Blocking
    private static void prepareImage(ImageMetaData metadata, ServerLevel level, ServerPlayer player, BlockHitResult ignoredBlockHitResult, WithFinishedMaps onSuccess) throws ImagePreparationException {
        MinecraftServer server = level.getServer();

        ImageDialogs.PREPARE.open(level, player);

        URL url = LinkVerifier.fromString(metadata.url());
        byte[] raw = ImageProvider.getRawImage(server, url);
        ItemStack[][] maps = ImageProvider.createMaps(level, raw, metadata);

        ImageDialogs.close(player);
        onSuccess.consume(maps);
    }

    private static void placeImage(ServerPlayer player, ServerLevel level, BlockHitResult blockHitResult, ItemStack[][] maps, ImageMetaData metadata) {
        Direction facing = blockHitResult.getDirection();
        BlockPos blockpos = blockHitResult.getBlockPos().relative(facing);

        String uuid = UUID.randomUUID().toString();

        int height = maps.length;
        int width = maps[0].length;
        long createdAt = System.currentTimeMillis();

        for (int y=0; y<height; y++) {
            for (int x=0; x<width; x++) {
                BlockPos curPos = offsetOnPainting(blockpos, facing, -x, y);
                ItemFrame itemFrame = createFramedImage(level, player.getStringUUID(), metadata.url(), createdAt, curPos, facing, maps[maps.length-1-y][x], uuid, metadata.transparent(), false);

                // Place Frame in world

                level.gameEvent(player, GameEvent.ENTITY_PLACE, itemFrame.position());
                level.addFreshEntity(itemFrame);
            }
        }

        playSound(level, (width * height), blockpos, SoundEvents.PAINTING_PLACE);
    }

    private interface WithFinishedMaps {
        void consume(ItemStack[][] maps);
    }

    private static void destroyImage(Player player, ServerLevel level, ItemFrame frame) {
        AtomicInteger size = new AtomicInteger();
        forWholeImage(level, frame, (framePart) -> {
            size.getAndIncrement();
            framePart.remove(Entity.RemovalReason.KILLED);
        });
        playSound(level, size.get(), frame.getPos(), SoundEvents.PAINTING_BREAK);

        // Returning item

        if (!player.isCreative()) {
            Vec3 centeredPos = frame.position();
            ItemStack defaultConsumable = getDefaultConsumable(level);
            dropItemAt(level, defaultConsumable, centeredPos);
        }
    }

    private static void forWholeImage(ServerLevel level, ItemFrame frame, ForFramePart applier) {
        AABB box = new AABB(
                frame.getX() - MAX_SIZE - 0.6, frame.getY() - MAX_SIZE - 0.6, frame.getZ() - MAX_SIZE - 0.6,
                frame.getX() + MAX_SIZE + 0.6, frame.getY() + MAX_SIZE + 0.6, frame.getZ() + MAX_SIZE + 0.6
        );

        UUID uuid = UUID.fromString(frame.getAttachedOrThrow(ID_TYPE));
        List<Entity> entities = level.getEntities(null, box).stream().filter((entry) -> {
            if (!(entry instanceof ItemFrame other) || !other.hasAttached(ID_TYPE))
                return false;

            UUID currentUUID = UUID.fromString(other.getAttachedOrThrow(ID_TYPE));
            return uuid.equals(currentUUID);
        }).toList();

        entities.forEach((entity) -> applier.apply((ItemFrame) entity));
    }

    interface ForFramePart {
        void apply(ItemFrame frame);
    }

    private static ItemFrame createFramedImage(ServerLevel level, String player, String url, long createdAt, BlockPos curPos, Direction facing, ItemStack map, String uuid, boolean transparent, boolean glow) {
        ItemFrame itemFrame;
        if (glow) {
            itemFrame = new GlowItemFrame(level, curPos, facing);
        } else {
            itemFrame = new ItemFrame(level, curPos, facing);
        }

        itemFrame.fixed = true;
        itemFrame.setInvisible(transparent);
        itemFrame.setItem(map);
        itemFrame.setAttached(ID_TYPE, uuid);
        itemFrame.setAttached(SIGNATURE_TYPE, Pair.of(player, url));
        itemFrame.setAttached(CREATED_AT_TYPE, createdAt);

        return itemFrame;
    }

    private static boolean isHoldingItem(Player player, InteractionHand hand) {
        ItemStack itemInHand = player.getItemInHand(hand);
        @Nullable CustomData customData = itemInHand.getComponents().get(DataComponents.CUSTOM_DATA);

        if (customData == null || itemInHand.getCount() < 1)
            return false;

        CompoundTag compoundTag = customData.copyTag();
        return compoundTag.getBoolean(IS_CUSTOM_IMAGE_TYPE).orElse(false);
    }

    private static BlockPos offsetOnPainting(BlockPos origin, Direction dir, int offX, int offY) {
        Direction rightDir;
        Direction upDir;

        boolean onWall = dir.getAxis().isHorizontal();
        int inverter = onWall ? 1 : -1;

        if (onWall) {
            rightDir = dir.getClockWise();
            upDir = Direction.UP;
        } else {
            rightDir = Direction.EAST;
            upDir = (dir == Direction.UP) ? Direction.SOUTH : Direction.NORTH;
        }

        return origin.offset(
                inverter * rightDir.getStepX() * offX + inverter * upDir.getStepX() * offY,
                inverter * rightDir.getStepY() * offX + inverter * upDir.getStepY() * offY,
                inverter * rightDir.getStepZ() * offX + inverter * upDir.getStepZ() * offY
        );
    }

    public static ItemStack getDefaultConsumable(ServerLevel level) {
        RecipeManager recipeManager = level.getServer().getRecipeManager();
        ResourceKey<@NotNull Recipe<?>> recipeKey = ResourceKey.create(Registries.RECIPE, Sivage.of("custom_image"));

        Optional<RecipeHolder<?>> opt = recipeManager.byKey(recipeKey);
        if (opt.isEmpty()) return getFallbackConsumable();

        Recipe<?> recipe = opt.get().value();

        if (recipe instanceof ShapedRecipe shaped) {
            return shaped.result.create();
        } else if (recipe instanceof ShapelessRecipe shapeless) {
            return shapeless.result.create();
        }

        return getFallbackConsumable();
    }

    private static ItemStack getFallbackConsumable() {
        ItemStack itemStack = Items.ENCHANTED_BOOK.getDefaultInstance();

        itemStack.set(DataComponents.RARITY, Rarity.UNCOMMON);
        itemStack.set(DataComponents.ITEM_NAME, Component.translatableWithFallback("item.sivage.custom_image", "Custom Image"));
        itemStack.set(DataComponents.ITEM_MODEL, Identifier.withDefaultNamespace("painting"));
        itemStack.set(DataComponents.MAX_STACK_SIZE, 64);
        itemStack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);

        CompoundTag compoundTag = new CompoundTag();
        compoundTag.putBoolean("custom_image", true);
        CustomData customData = CustomData.of(compoundTag);
        itemStack.set(DataComponents.CUSTOM_DATA, customData);

        return itemStack;
    }

    private static void dropItemAt(ServerLevel level, ItemStack stack, Vec3 pos) {
        if (stack.isEmpty())
            return;

        ItemEntity itemEntity = new ItemEntity(level, pos.x, pos.y, pos.z, stack);
        itemEntity.setDefaultPickUpDelay();
        level.addFreshEntity(itemEntity);
    }

    private static void sendImageInfo(ServerPlayer viewer, ServerLevel level, ItemFrame frame) {
        @Nullable Pair<String, String> signature = frame.getAttached(SIGNATURE_TYPE);
        if (signature == null) {
            return;
        }

        Component creatorName;
        try {
            Player creator = level.getPlayerInAnyDimension(UUID.fromString(signature.getFirst()));
            creatorName = creator == null
                    ? Component.translatableWithFallback("sivage.chat.signature.unknown", "[Unknown]")
                    : creator.getDisplayName();
        } catch (IllegalArgumentException ignored) {
            creatorName = Component.translatableWithFallback("sivage.chat.signature.unknown", "[Unknown]");
        }

        @Nullable Long createdAt = frame.getAttached(CREATED_AT_TYPE);
        Component createdAtText = createdAt == null || createdAt <= 0
                ? Component.translatableWithFallback("sivage.chat.signature.unknown", "[Unknown]")
                : Component.literal(CREATED_AT_FORMAT.format(Instant.ofEpochMilli(createdAt))).withStyle(ChatFormatting.YELLOW);

        MutableComponent info = Component.empty()
                .append(Component.translatableWithFallback(
                        "sivage.chat.signature.info.header",
                        "Custom Image Info"
                ).withStyle(ChatFormatting.GOLD))
                .append(Component.literal("\n"))
                .append(Component.translatableWithFallback(
                        "sivage.chat.signature.url",
                        " - URL: %s",
                        Component.literal(signature.getSecond()).withStyle(Style.EMPTY.withColor(ChatFormatting.BLUE).withUnderlined(true))
                ).withStyle(ChatFormatting.GRAY))
                .append(Component.literal("\n"))
                .append(Component.translatableWithFallback(
                        "sivage.chat.signature.created_at",
                        " - Created at: %s",
                        createdAtText
                ).withStyle(ChatFormatting.GRAY))
                .append(Component.literal("\n"))
                .append(Component.translatableWithFallback(
                        "sivage.chat.signature.creator",
                        " - Created by: %s",
                        Component.empty().append(creatorName).withStyle(ChatFormatting.AQUA)
                ).withStyle(ChatFormatting.GRAY));

        viewer.sendSystemMessage(info);
    }

    private static Optional<Boolean> reserveImageSlot(ServerPlayer player) {
        if (SivagePermissions.canBypassImageLimit(player))
            return Optional.of(false);

        synchronized (PENDING_IMAGE_CREATIONS) {
            UUID playerId = player.getUUID();
            int activeImages = countOwnedImages(player);
            int pendingImages = PENDING_IMAGE_CREATIONS.getOrDefault(playerId, 0);

            if (activeImages + pendingImages >= MAX_IMAGES_PER_PLAYER)
                return Optional.empty();

            PENDING_IMAGE_CREATIONS.put(playerId, pendingImages + 1);
            return Optional.of(true);
        }
    }

    private static void releaseImageSlot(UUID playerId) {
        synchronized (PENDING_IMAGE_CREATIONS) {
            int pendingImages = PENDING_IMAGE_CREATIONS.getOrDefault(playerId, 0);
            if (pendingImages <= 1) {
                PENDING_IMAGE_CREATIONS.remove(playerId);
            } else {
                PENDING_IMAGE_CREATIONS.put(playerId, pendingImages - 1);
            }
        }
    }

    private static int countOwnedImages(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null)
            return 0;

        String playerId = player.getStringUUID();
        Set<UUID> imageIds = new HashSet<>();

        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof ItemFrame frame) || !frame.hasAttached(ID_TYPE) || !frame.hasAttached(SIGNATURE_TYPE))
                    continue;

                Pair<String, String> signature = frame.getAttachedOrThrow(SIGNATURE_TYPE);
                if (playerId.equals(signature.getFirst())) {
                    imageIds.add(UUID.fromString(frame.getAttachedOrThrow(ID_TYPE)));
                }
            }
        }

        return imageIds.size();
    }

    private static void playSound(ServerLevel level, int size, BlockPos blockpos, SoundEvent sound) {
        float pitch = 1f - .75f / (MAX_SIZE*MAX_SIZE) * size;
        level.playSound(null, blockpos, sound, SoundSource.BLOCKS, 1f, pitch);
    }
}
