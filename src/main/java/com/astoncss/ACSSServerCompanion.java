package com.astoncss;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.CommandManager.RegistrationEnvironment;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.TeleportTarget;
import net.minecraft.world.World;
import net.minecraft.world.TeleportTarget.PostDimensionTransition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import org.geysermc.floodgate.api.FloodgateApi;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;

public class ACSSServerCompanion implements ModInitializer {
    public static final String MOD_ID = "acss-server-companion";
    public static final String WHITELIST = "acss-whitelist.txt";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private FloodgateApi floodgateApi = null;
    private MemberMap verifiedMembers = new MemberMap();
    private File whitelistFile = new File(String.valueOf(FabricLoader.getInstance().getConfigDir()), WHITELIST);
    private ArrayList<UUID> teleportOnJoin = new ArrayList<>();

    @Override
    public void onInitialize() {
        LOGGER.info("Loading ACSS Companion mod");
        loadWhitelist();
        CommandRegistrationCallback.EVENT.register(this::verificationCommands);
        ServerPlayConnectionEvents.JOIN.register(this::greetPlayer);
        ServerWorldEvents.LOAD.register((server, world) -> {
            floodgateApi = FloodgateApi.getInstance();
        });
    }

    private void loadWhitelist() {
        if (whitelistFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(whitelistFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    boolean shouldTP = false;
                    // account was verified while not on the server
                    // the whitespace is intentional. the line should not end with whitespace
                    if (line.endsWith(" OFFLINE")) {
                        line = line.substring(0, line.length() - " OFFLINE".length());
                        LOGGER.info("{} was added to the whitelist manually, fixing.", line);
                        shouldTP = true;
                    }
                    String[] parts = line.split("\\s+");

                    // one account -> length = 5. two accounts -> length = 8
                    if (!(parts.length == 5 || parts.length == 8)) {
                        LOGGER.warn("Invalid line in acss whitelist: {}. Too many or too few elements. Skipping.", line);
                        continue;
                    }

                    ACSSMember m = new ACSSMember(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
                    PlayerConnection con = (parts[2].equalsIgnoreCase("JAVA")) ? PlayerConnection.Online : (parts[2].equals("BEDROCK")) ? PlayerConnection.Floodgate : null;

                    if (con == null) {
                        LOGGER.warn("Invalid line in acss whitelist: {}. Unkown account type. Skipping.", line);
                        continue;
                    }

                    UUID uuid;
                    try {
                        uuid = UUID.fromString(parts[3]);
                    } catch (IllegalArgumentException e) {
                        LOGGER.warn("Invalid line in acss whitelist: {}. Invalid UUID. Skipping.", line);
                        continue;
                    }
                    String playerName = parts[4];
                    verifiedMembers.addPlayer(m, uuid, playerName, con);

                    // second account if registered
                    if (parts.length == 8) {
                        PlayerConnection con2 = (parts[5].equalsIgnoreCase("JAVA")) ? PlayerConnection.Online : (parts[5].equals("BEDROCK")) ? PlayerConnection.Floodgate : null;

                        if (con2 == null) {
                            LOGGER.warn("Invalid line in acss whitelist: {}. Unkown account type. Skipping.", line);
                            LOGGER.warn(parts[5]);
                            continue;
                        }

                        UUID uuid2;
                        try {
                            uuid2 = UUID.fromString(parts[6]);
                        } catch (IllegalArgumentException e) {
                            LOGGER.warn("Invalid line in acss whitelist: {}. Invalid UUID. Skipping.", line);
                            continue;
                        }
                        String playerName2 = parts[7];
                        verifiedMembers.addPlayer(m, uuid2, playerName2, con2);
                        if (shouldTP) {
                            teleportOnJoin.add(uuid2);
                        }
                    }
                    if (shouldTP) {
                        teleportOnJoin.add(uuid);
                    }
                }
            } catch (IOException e) {
                LOGGER.error(e.getMessage());
            }
            // sync file to fix any OFFLINE lines
            verifiedMembers.writeMap(whitelistFile);
        } else {
            LOGGER.info("Creating ACSS whitelist file...");
            try {
                whitelistFile.createNewFile();
            } catch (IOException e) {
                LOGGER.error("Failed to create whitelist file: {}", e);
            }
        }
    }

    private void verificationCommands(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess, RegistrationEnvironment environment) {
        dispatcher.register(CommandManager
            .literal("verify")
            .executes(ACSSServerCompanion::verifyHelp)
            .then(CommandManager.literal("help")
                .executes(ACSSServerCompanion::verifyHelp))
            .then(CommandManager.literal("id")
                .then(CommandManager.argument("student id", IntegerArgumentType.integer())
                    .executes(this::idVerify)))
            .then(CommandManager.literal("code")
                .then(CommandManager.argument("verification code", IntegerArgumentType.integer())
                    .executes(this::codeVerify)))
            .then(CommandManager.literal("manual")
                .executes(ACSSServerCompanion::manualVerify))
            .then(CommandManager.literal("discord")
                .executes(this::discordVerify)));

        dispatcher.register(CommandManager
            .literal("adminVerify").requires(source -> source.hasPermissionLevel(4))
            .then(CommandManager.argument("player", EntityArgumentType.player())
                .then(CommandManager.argument("student id", IntegerArgumentType.integer())
                    .executes(this::adminVerify))));

        dispatcher.register(CommandManager
            .literal("acssWhitelistReload")
            .requires(source -> source.hasPermissionLevel(4))
            .executes(this::reloadWhitelist));
    }

    private int reloadWhitelist(CommandContext<ServerCommandSource> context) {
        LOGGER.info("Reloading ACSS whitelist...");
        verifiedMembers = new MemberMap();
        loadWhitelist();
        context.getSource().sendFeedback(() -> MessageFormatter.makeMessage("Reloaded whitelist. Check Server log for errors."), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int verifyHelp(CommandContext<ServerCommandSource> context) {
        context.getSource().sendFeedback(() -> MessageFormatter.makeMessage("/verify HELP"), false);
        String[] lines = {
            // TODO: email verification
            // "To verify, type /verify id STUDENT_ID. e.g. /verify id 123456789",
            // "",
            // "Once you have received your code, type /verify code CODE. e.g. /verify code 532647",
            // "",
            // "If you're having trouble verifying at any point, please open a ticket in our Discord server.",
            "To verify, type /verify manual.",
            "",
            "You will need to send your Student ID and the output of that command to a ACSS committee member.",
        };
        for (String l : lines) {
            context.getSource().sendFeedback(() -> Text.literal(l), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int manualVerify(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        source.sendFeedback(() -> MessageFormatter.makeMessage("Please send your student ID as well as the following information to an ACSS committee member. You can open a ticket on Discord for this."), false);
        source.sendFeedback(() -> MessageFormatter.makeMessage("Verify: " + source.getPlayer().getNameForScoreboard() + " " + source.getPlayer().getUuidAsString()), true);
        return Command.SINGLE_SUCCESS;
    }

    private int adminVerify(CommandContext<ServerCommandSource> context) {
        ServerPlayerEntity player;
        int studentId;
        try {
            player = EntityArgumentType.getPlayer(context, "player");
            studentId = IntegerArgumentType.getInteger(context, "student id");
        } catch (CommandSyntaxException e) {
            context.getSource().sendFeedback(() -> Text.literal("adminVerify failed: " + e.getMessage()), true);
            return -1;
        }
        String playerName = player.getNameForScoreboard().toString();
        UUID uuid = player.getUuid();
        PlayerConnection con = floodgateApi.isFloodgatePlayer(uuid) ? PlayerConnection.Floodgate : PlayerConnection.Online;

        if (verifiedMembers.addPlayer(new ACSSMember(studentId), uuid, playerName, con) != 0) {
            context.getSource().sendFeedback(() -> Text.literal("Failed to verify " + playerName + " because " + studentId + " already has an account verified on this platform. If this is an error, please check the whitelist file manually."), true);
            return Command.SINGLE_SUCCESS;
        }
        verifiedMembers.writeMap(whitelistFile);

        ServerWorld w = context.getSource().getWorld();
        TeleportTarget tp = new TeleportTarget(w, w.getSpawnPos().toCenterPos(), Vec3d.ZERO, w.getSpawnAngle(), 0.0f, TeleportTarget.NO_OP);
        player.teleportTo(tp);
        player.networkHandler.disconnect(Text.literal("You have been verified successfully. Reconnect to the server to start playing!"));

        context.getSource().sendFeedback(() -> Text.literal("Successfully verified " + playerName + " as " + studentId + "."), true);
        return Command.SINGLE_SUCCESS;
    }

    // TODO
    private int idVerify(CommandContext<ServerCommandSource> context) {
        context.getSource().sendFeedback(() -> MessageFormatter.makeMessage("If you are an ACSS member, you will shortly receive an email with a verification code."), false);
        context.getSource().sendFeedback(() -> Text.literal("Email verification is not implemented yet."), false);
        return Command.SINGLE_SUCCESS;
    }

    private int codeVerify(CommandContext<ServerCommandSource> context) {
        context.getSource().sendFeedback(() -> Text.literal("Email verification is not implemented yet."), false);
        return Command.SINGLE_SUCCESS;
    }

    private int discordVerify(CommandContext<ServerCommandSource> context) {
        context.getSource().sendFeedback(() -> Text.literal("Discord verification is not implemented yet."), false);
        return Command.SINGLE_SUCCESS;
    }
    // TODO

    private void greetPlayer(ServerPlayNetworkHandler handler, PacketSender sender, MinecraftServer server) {
        // TP players verified offline back to spawn when they join
        int tp_player = teleportOnJoin.indexOf(handler.player.getUuid());
        if (tp_player != -1) {
            ServerWorld w = server.getWorld(World.OVERWORLD);
            TeleportTarget tp = new TeleportTarget(w, w.getSpawnPos().toCenterPos(), Vec3d.ZERO, w.getSpawnAngle(), 0.0f, TeleportTarget.NO_OP);
            handler.player.teleportTo(tp);
            teleportOnJoin.remove(tp_player);
        }

        if (handler.player.hasPermissionLevel(4)) {
            handler.player.sendMessage(MessageFormatter.makeMessage("MSPT: " + server.getAverageTickTime() + " TPS: " + Math.min(20.0, 1 / (server.getAverageTickTime() / 1000))));
            handler.player.sendMessage(MessageFormatter.makeMessage("Players online: " + Arrays.asList(server.getPlayerNames()).stream().collect(Collectors.joining(" "))));
        } else if (verifiedMembers.isRegistered(handler.player.getUuid())) {
            handler.player.changeGameMode(GameMode.SURVIVAL);
            handler.player.sendMessage(MessageFormatter.makeMessage("Welcome back. Please respect others and play by the rules. Good luck and have fun!"));
        }  else {
            handler.player.changeGameMode(GameMode.SPECTATOR);

            handler.player.sendMessage(MessageFormatter.makeMessage("You are not verified on the server. You need to be an ACSS member to play on this server."));
            handler.player.sendMessage(Text.literal(""));
            handler.player.sendMessage(MessageFormatter.makeMessage("To start verifying, use the /verify command. Usage: `/verify manual`."));
            handler.player.sendMessage(Text.literal(""));
            handler.player.sendMessage(MessageFormatter.makeMessage(Text.literal("NOTICE: You can only be verified manually by an admin until email verification is set up.").formatted(Formatting.BOLD)));
        }
    }
}
