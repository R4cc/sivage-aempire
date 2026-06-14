package dev.kleinbox.sivage;

import dev.kleinbox.sivage.command.CheckSignatureCommand;
import dev.kleinbox.sivage.command.GetItemCommand;
import dev.kleinbox.sivage.command.ImageAdminCommand;
import dev.kleinbox.sivage.item.ImageItem;
import dev.kleinbox.sivage.packet.C2SCustomActionEvent;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Sivage implements ModInitializer {
	public static final String MOD_ID = "sivage";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final Config CONFIG = Config.createToml(FabricLoader.getInstance().getConfigDir(), MOD_ID,
            "server", Config.class);

    @Override
	public void onInitialize() {
        UseBlockCallback.EVENT.register(ImageItem::onBlockUse);
        UseEntityCallback.EVENT.register(ImageItem::onEntityUse);
        C2SCustomActionEvent.ON_RECEIVE.register(ImageItem::onPlace);
        AttackEntityCallback.EVENT.register(ImageItem::onDestroy);
        LOGGER.info("Registered events for Custom Images!");

        //noinspection UnstableApiUsage
        LOGGER.info("Registered DataAttachment {}", ImageItem.ID_TYPE.identifier());

        registerCommands();
    }

    private static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal(MOD_ID)
                    .then(Commands.literal("check")
                            .requires(SivagePermissions::canCheck)
                            .then(Commands.argument("image", EntityArgument.entity())
                                    .executes(CheckSignatureCommand::executeCheckSignature))
                    )
                    .then(Commands.literal("item")
                            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                            .executes(GetItemCommand::executeGetItemForOne)
                            .then(Commands.argument("for", EntityArgument.players())
                                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                    .executes(GetItemCommand::executeGetItemForMany))
                    )
                    .then(Commands.literal("images")
                            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                            .then(Commands.literal("list")
                                    .executes(ImageAdminCommand::executeListAll)
                                    .then(Commands.argument("user", ImageAdminCommand.userArgument())
                                            .executes(ImageAdminCommand::executeListForUser))
                            )
                            .then(Commands.literal("delete")
                                    .then(Commands.argument("user", ImageAdminCommand.userArgument())
                                            .executes(ImageAdminCommand::executeDeleteForUser))
                            )
                    )
            );

            LOGGER.info("Commands have been registered!");
        });
    }

    @SuppressWarnings("SameParameterValue")
    public static Identifier of(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
