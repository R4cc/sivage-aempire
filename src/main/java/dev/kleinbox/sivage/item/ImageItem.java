package dev.kleinbox.sivage.item;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.kleinbox.sivage.Sivage;
import dev.kleinbox.sivage.SivagePermissions;
import dev.kleinbox.sivage.image.ImagePreparationException;
import dev.kleinbox.sivage.image.ImageProvider;
import dev.kleinbox.sivage.image.ImageRequestCoordinator;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.TraceableEntity;
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
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URL;
import java.net.Proxy;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
    public static final int MAX_SUPPORTED_SIZE = 8;
    private static final DateTimeFormatter CREATED_AT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault());

    private static ImageRequestCoordinator requestCoordinator =
            new ImageRequestCoordinator(Sivage.CONFIG.network.maxConcurrentRequests);
    private static final HashMap<UUID, RequestState> REQUESTS = new HashMap<>();
    private static final HashMap<UUID, Integer> PENDING_IMAGE_CREATIONS = new HashMap<>();

    public static final MutableComponent LIMIT_REACHED_MSG = Component.translatableWithFallback("sivage.hud.limit_reached", "Please wait for the previous image to finish generating.");
    public static final MutableComponent SERVER_BUSY_MSG = Component.translatableWithFallback("sivage.hud.server_busy", "The image service is busy. Please try again shortly.");

    public static boolean isStillGenerating(UUID uuid) {
        return coordinator().isActive(uuid);
    }

    private static ImageRequestCoordinator coordinator() {
        if (requestCoordinator.isShutdown())
            requestCoordinator = new ImageRequestCoordinator(Sivage.CONFIG.network.maxConcurrentRequests);
        return requestCoordinator;
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

        @Nullable UUID imageId = getImageId(frame);
        @Nullable Pair<String, String> signature = frame.getAttached(SIGNATURE_TYPE);
        if (imageId == null || signature == null) {
            Sivage.LOGGER.warn("Ignoring edit request for Sivage frame {} with malformed metadata.", frame.getUUID());
            return InteractionResult.FAIL;
        }

        if (!player.isCreative())
            item.consume(1, null);

        String uuid = imageId.toString();
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
     * Clears the vanilla invulnerability flag from images created by older Sivage versions.
     */
    public static void onEntityLoad(Entity entity, ServerLevel ignoredLevel) {
        if (entity instanceof ItemFrame frame && frame.hasAttached(ID_TYPE))
            frame.fixed = false;
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
                submittedMetadata.getBlockWidth(),
                submittedMetadata.getBlockHeight(),
                submittedMetadata.stretch(),
                Sivage.CONFIG.game.invisibleFrames,
                submittedMetadata.dithering(),
                submittedMetadata.nearestNeighbor()
        );
        ServerLevel level = player.level();

        if (!SivagePermissions.canCreate(player)) {
            ImageDialogs.close(player);
            player.sendSystemMessage(SivagePermissions.CREATE_DENIED);
            return true;
        }

        if (isStillGenerating(player.getUUID())) {
            ImageDialogs.close(player);
            player.sendOverlayMessage(LIMIT_REACHED_MSG);
            return true;
        }

        // Get block for frame to be placed

        double reach = player.getAttributes().getValue(Attributes.BLOCK_INTERACTION_RANGE);
        HitResult hitResult = player.pick(reach, 0f, false);
        if (hitResult.getType() != HitResult.Type.BLOCK) {
            ImageDialogs.close(player); // Not looking at block
            return true;
        }
        BlockHitResult blockHitResult = (BlockHitResult) hitResult;

        URL url;
        try {
            url = LinkVerifier.fromString(metadata.url());
        } catch (LinkVerifier.BlockedLinkException e) {
            ImageDialogs.openBlockedUrl(player, e);
            return true;
        } catch (ImagePreparationException e) {
            e.getDialog().open(level, player);
            return true;
        }

        Optional<Boolean> imageSlotReservation = reserveImageSlot(player);
        if (imageSlotReservation.isEmpty()) {
            ImageDialogs.close(player);
            player.sendOverlayMessage(getImageLimitReachedMessage(player));
            return true;
        }
        boolean imageSlotReserved = imageSlotReservation.get();

        CountDownLatch requestReady = new CountDownLatch(1);
        AtomicReference<RequestState> acceptedRequest = new AtomicReference<>();
        boolean submitted = coordinator().submit(player.getUUID(), () -> {
            try {
                requestReady.await();
                RequestState request = acceptedRequest.get();
                if (request != null)
                    prepareImage(request);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        if (!submitted) {
            if (imageSlotReserved)
                releaseImageSlot(player.getUUID());
            ImageDialogs.close(player);
            player.sendOverlayMessage(SERVER_BUSY_MSG);
            return true;
        }

        ItemStack consumed = ItemStack.EMPTY;
        if (!player.isCreative()) {
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
                coordinator().cancel(player.getUUID());
                requestReady.countDown();
                if (imageSlotReserved) releaseImageSlot(player.getUUID());
                ImageDialogs.close(player); // No item to consume
                return true;
            }
        }

        Proxy proxy = level.getServer().getProxy();
        RequestState request = new RequestState(
                player,
                level,
                blockHitResult,
                metadata,
                url,
                proxy,
                consumed,
                imageSlotReserved
        );
        REQUESTS.put(player.getUUID(), request);
        acceptedRequest.set(request);
        requestReady.countDown();

        ImageDialogs.PREPARE.open(level, player);

        return true;
    }

    private static void prepareImage(RequestState request) {
        try {
            byte[] raw = ImageProvider.getRawImage(request.proxy, request.url, request.requester());
            byte[] colors = ImageProvider.renderMapColors(raw, request.metadata);
            request.server.execute(() -> completeRequest(request, colors));
        } catch (ImagePreparationException e) {
            request.server.execute(() -> failRequest(request, e));
        } catch (Throwable e) {
            request.server.execute(() -> failRequest(request, e));
        }
    }

    /**
     * <p>Called when player attempts to destroy an entity (like our image within the world).</p>
     */
    public static InteractionResult onDestroy(Player player, Level level, InteractionHand ignoredInteractionHand, Entity entity, @Nullable EntityHitResult ignoredEntityHitResult) {
        if (level.isClientSide())
            return InteractionResult.PASS;

        if (!(entity instanceof ItemFrame frame && frame.hasAttached(ID_TYPE)))
            return InteractionResult.PASS;

        if (player instanceof ServerPlayer serverPlayer) {
            if (!canPlayerRemoveImage(serverPlayer, frame, true))
                return InteractionResult.FAIL;
        }

        destroyImage((ServerLevel) level, frame, player);

        return InteractionResult.SUCCESS;
    }

    private static void completeRequest(RequestState request, byte[] colors) {
        if (!isCurrentRequest(request))
            return;

        ServerPlayer player = request.player;
        try {
            if (!canComplete(request)) {
                refundToPlayer(request);
                ImageDialogs.close(player);
                return;
            }
            if (!SivagePermissions.canCreate(player)) {
                refundToPlayer(request);
                ImageDialogs.close(player);
                player.sendSystemMessage(SivagePermissions.CREATE_DENIED);
                return;
            }
            if (!isReservedImageSlotValid(player, request)) {
                refundToPlayer(request);
                ImageDialogs.close(player);
                player.sendOverlayMessage(getImageLimitReachedMessage(player));
                return;
            }

            ItemStack placementItem = request.consumed.isEmpty()
                    ? getDefaultConsumable(request.level)
                    : request.consumed;
            if (!canPlaceImage(player, request.level, request.blockHitResult, request.metadata, placementItem)) {
                refundToPlayer(request);
                ImageDialogs.close(player);
                return;
            }

            ItemStack[][] maps = ImageProvider.createMaps(request.level, colors, request.metadata);
            if (!placeImage(player, request.level, request.blockHitResult, maps, request.metadata)) {
                refundToPlayer(request);
                ImageDialogs.EXCEPTION.open(request.level, player);
                Sivage.LOGGER.error("Image placement for {} failed after successful preparation.", request.requester());
                return;
            }
            ImageDialogs.close(player);
        } catch (Throwable e) {
            refundToPlayer(request);
            Sivage.LOGGER.error("Image request for {} failed while committing on the server thread.", request.requester(), e);
            ImageDialogs.EXCEPTION.open(request.level, player);
        } finally {
            finishRequest(request);
        }
    }

    private static void failRequest(RequestState request, Throwable failure) {
        if (!isCurrentRequest(request))
            return;

        try {
            refundToPlayer(request);
            if (failure instanceof LinkVerifier.BlockedLinkException blocked) {
                ImageDialogs.openBlockedUrl(request.player, blocked);
            } else if (failure instanceof ImagePreparationException preparation) {
                preparation.getDialog().open(request.level, request.player);
            } else {
                Sivage.LOGGER.error("Image request for {} failed!", request.requester(), failure);
                ImageDialogs.EXCEPTION.open(request.level, request.player);
            }
        } finally {
            finishRequest(request);
        }
    }

    private static boolean canComplete(RequestState request) {
        ServerPlayer player = request.player;
        MinecraftServer server = request.level.getServer();
        if (server.getPlayerList().getPlayer(player.getUUID()) != player || player.isRemoved()
                || !player.isAlive() || player.isSpectator() || player.level() != request.level)
            return false;

        double reach = player.getAttributes().getValue(Attributes.BLOCK_INTERACTION_RANGE) + 1.0D;
        return player.distanceToSqr(Vec3.atCenterOf(request.blockHitResult.getBlockPos())) <= reach * reach;
    }

    private static boolean canPlaceImage(ServerPlayer player, ServerLevel level, BlockHitResult hit,
                                         ImageMetaData metadata, ItemStack placementItem) {
        Direction facing = hit.getDirection();
        BlockPos origin = hit.getBlockPos().relative(facing);
        for (int y = 0; y < metadata.getBlockHeight(); y++) {
            for (int x = 0; x < metadata.getBlockWidth(); x++) {
                BlockPos position = ImageGeometry.offset(origin, facing, -x, y);
                if (!level.isLoaded(position) || !level.isInWorldBounds(position)
                        || !level.getWorldBorder().isWithinBounds(position)
                        || !player.mayUseItemAt(position, facing, placementItem))
                    return false;

                ItemFrame probe = new ItemFrame(level, position, facing);
                if (!probe.survives())
                    return false;
            }
        }
        return true;
    }

    private static boolean placeImage(ServerPlayer player, ServerLevel level, BlockHitResult blockHitResult, ItemStack[][] maps, ImageMetaData metadata) {
        Direction facing = blockHitResult.getDirection();
        BlockPos blockpos = blockHitResult.getBlockPos().relative(facing);

        String uuid = UUID.randomUUID().toString();

        int height = maps.length;
        int width = maps[0].length;
        long createdAt = System.currentTimeMillis();
        List<ItemFrame> added = new java.util.ArrayList<>(width * height);

        for (int y=0; y<height; y++) {
            for (int x=0; x<width; x++) {
                BlockPos curPos = ImageGeometry.offset(blockpos, facing, -x, y);
                ItemFrame itemFrame = createFramedImage(level, player.getStringUUID(), metadata.url(), createdAt, curPos, facing, maps[maps.length-1-y][x], uuid, metadata.transparent(), false);
                if (!level.addFreshEntity(itemFrame)) {
                    added.forEach(Entity::discard);
                    return false;
                }
                added.add(itemFrame);
                level.gameEvent(player, GameEvent.ENTITY_PLACE, itemFrame.position());
            }
        }

        playSound(level, (width * height), blockpos, SoundEvents.PAINTING_PLACE);
        return true;
    }

    public static void destroyImage(ServerLevel level, ItemFrame frame, @Nullable Entity breaker) {
        AtomicInteger size = new AtomicInteger();
        forWholeImage(level, frame, (framePart) -> {
            size.getAndIncrement();
            framePart.kill(level);
        });

        if (!level.getGameRules().get(GameRules.ENTITY_DROPS))
            return;

        playSound(level, Math.max(1, size.get()), frame.getPos(), SoundEvents.PAINTING_BREAK);

        if (breaker instanceof Player player && player.hasInfiniteMaterials())
            return;

        Vec3 centeredPos = frame.position();
        ItemStack defaultConsumable = getDefaultConsumable(level);
        dropItemAt(level, defaultConsumable, centeredPos);
    }

    public static boolean canDamageImage(ItemFrame frame, DamageSource damageSource) {
        @Nullable Entity responsible = damageSource.getEntity();
        if (!(responsible instanceof Player)) {
            Entity direct = damageSource.getDirectEntity();
            if (direct instanceof TraceableEntity traceable)
                responsible = traceable.getOwner();
        }

        if (responsible instanceof ServerPlayer player)
            return canPlayerRemoveImage(player, frame, true);
        return true;
    }

    private static boolean canPlayerRemoveImage(ServerPlayer player, ItemFrame frame, boolean notify) {
        @Nullable Pair<String, String> signature = frame.getAttached(SIGNATURE_TYPE);
        boolean isOwner = signature != null && player.getStringUUID().equals(signature.getFirst());
        boolean allowed = isOwner || SivagePermissions.canRemoveAnyImage(player);
        if (!allowed && notify)
            player.sendSystemMessage(SivagePermissions.REMOVE_DENIED);
        return allowed;
    }

    private static void forWholeImage(ServerLevel level, ItemFrame frame, ForFramePart applier) {
        AABB box = new AABB(
                frame.getX() - MAX_SUPPORTED_SIZE - 0.6, frame.getY() - MAX_SUPPORTED_SIZE - 0.6, frame.getZ() - MAX_SUPPORTED_SIZE - 0.6,
                frame.getX() + MAX_SUPPORTED_SIZE + 0.6, frame.getY() + MAX_SUPPORTED_SIZE + 0.6, frame.getZ() + MAX_SUPPORTED_SIZE + 0.6
        );

        @Nullable UUID uuid = getImageId(frame);
        if (uuid == null) {
            applier.apply(frame);
            return;
        }
        List<Entity> entities = level.getEntities(null, box).stream().filter((entry) -> {
            if (!(entry instanceof ItemFrame other) || !other.hasAttached(ID_TYPE))
                return false;

            @Nullable UUID currentUUID = getImageId(other);
            return currentUUID != null && uuid.equals(currentUUID);
        }).toList();

        entities.forEach((entity) -> applier.apply((ItemFrame) entity));
    }

    interface ForFramePart {
        void apply(ItemFrame frame);
    }

    public static @Nullable UUID getImageId(ItemFrame frame) {
        @Nullable String raw = frame.getAttached(ID_TYPE);
        if (raw == null)
            return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            Sivage.LOGGER.warn("Ignoring malformed Sivage image id '{}' on frame {}.", raw, frame.getUUID());
            return null;
        }
    }

    private static ItemFrame createFramedImage(ServerLevel level, String player, String url, long createdAt, BlockPos curPos, Direction facing, ItemStack map, String uuid, boolean transparent, boolean glow) {
        ItemFrame itemFrame;
        if (glow) {
            itemFrame = new GlowItemFrame(level, curPos, facing);
        } else {
            itemFrame = new ItemFrame(level, curPos, facing);
        }

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
        int maxImagesPerPlayer = SivagePermissions.getImageLimit(player, Sivage.CONFIG.game.maxImagesPerPlayer);
        if (maxImagesPerPlayer <= 0)
            return Optional.of(false);

        synchronized (PENDING_IMAGE_CREATIONS) {
            UUID playerId = player.getUUID();
            int activeImages = countOwnedImages(player);
            int pendingImages = PENDING_IMAGE_CREATIONS.getOrDefault(playerId, 0);

            if (activeImages + pendingImages >= maxImagesPerPlayer)
                return Optional.empty();

            PENDING_IMAGE_CREATIONS.put(playerId, pendingImages + 1);
            return Optional.of(true);
        }
    }

    private static boolean isReservedImageSlotValid(ServerPlayer player, RequestState request) {
        int maxImagesPerPlayer = SivagePermissions.getImageLimit(player, Sivage.CONFIG.game.maxImagesPerPlayer);
        if (maxImagesPerPlayer <= 0)
            return true;

        synchronized (PENDING_IMAGE_CREATIONS) {
            int activeImages = countOwnedImages(player);
            int pendingImages = PENDING_IMAGE_CREATIONS.getOrDefault(player.getUUID(), 0);
            int unreservedCurrentRequest = request.imageSlotReserved ? 0 : 1;
            return activeImages + pendingImages + unreservedCurrentRequest <= maxImagesPerPlayer;
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

    private static boolean isCurrentRequest(RequestState request) {
        return !request.finished && REQUESTS.get(request.player.getUUID()) == request;
    }

    private static void finishRequest(RequestState request) {
        if (request.finished)
            return;
        request.finished = true;
        UUID playerId = request.player.getUUID();
        REQUESTS.remove(playerId, request);
        coordinator().finish(playerId);
        if (request.imageSlotReserved)
            releaseImageSlot(playerId);
    }

    private static void refundToPlayer(RequestState request) {
        if (request.refunded || request.consumed.isEmpty())
            return;
        request.refunded = true;

        ItemStack refund = request.consumed.copy();
        request.player.getInventory().add(refund);
        if (!refund.isEmpty())
            request.player.drop(refund, true, false);
    }

    public static void onPlayerDisconnect(ServerPlayer player) {
        RequestState request = REQUESTS.get(player.getUUID());
        if (request == null)
            return;
        coordinator().cancel(player.getUUID());
        refundToPlayer(request);
        finishRequest(request);
    }

    public static void onServerStopping(MinecraftServer ignoredServer) {
        for (RequestState request : List.copyOf(REQUESTS.values())) {
            coordinator().cancel(request.player.getUUID());
            refundToPlayer(request);
            finishRequest(request);
        }
        requestCoordinator.shutdown();
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
                    @Nullable UUID imageId = getImageId(frame);
                    if (imageId != null)
                        imageIds.add(imageId);
                }
            }
        }

        return imageIds.size();
    }

    private static void playSound(ServerLevel level, int size, BlockPos blockpos, SoundEvent sound) {
        float maxSize = Sivage.CONFIG.game.maxSize;
        float pitch = 1f - .75f / (maxSize * maxSize) * size;
        level.playSound(null, blockpos, sound, SoundSource.BLOCKS, 1f, pitch);
    }

    private static MutableComponent getImageLimitReachedMessage(ServerPlayer player) {
        return Component.translatableWithFallback(
                "sivage.hud.image_limit_reached",
                "You can only have %s Sivage images placed at a time.",
                SivagePermissions.getImageLimit(player, Sivage.CONFIG.game.maxImagesPerPlayer)
        );
    }

    private static final class RequestState {
        private final ServerPlayer player;
        private final ServerLevel level;
        private final MinecraftServer server;
        private final BlockHitResult blockHitResult;
        private final ImageMetaData metadata;
        private final URL url;
        private final Proxy proxy;
        private final ItemStack consumed;
        private final boolean imageSlotReserved;
        private final String requester;
        private boolean refunded;
        private boolean finished;

        private RequestState(ServerPlayer player, ServerLevel level, BlockHitResult blockHitResult,
                             ImageMetaData metadata, URL url, Proxy proxy, ItemStack consumed,
                             boolean imageSlotReserved) {
            this.player = player;
            this.level = level;
            this.server = level.getServer();
            this.blockHitResult = blockHitResult;
            this.metadata = metadata;
            this.url = url;
            this.proxy = proxy;
            this.consumed = consumed;
            this.imageSlotReserved = imageSlotReserved;
            this.requester = player.getPlainTextName() + " (" + player.getStringUUID() + ")";
        }

        private String requester() {
            return requester;
        }
    }
}
