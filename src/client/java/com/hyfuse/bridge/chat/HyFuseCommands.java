package com.hyfuse.bridge.chat;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.network.chat.Component;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * HyFuseCommands — the /hyfuse client command tree (thin Brigadier wrapper).
 *
 * Local-only: client commands never leave the client, so /hyfuse is usable
 * only by the user at the bot's keyboard (other players cannot see or
 * invoke it). All logic lives in {@link com.hyfuse.bridge.agent.HyFuseCommandCore}.
 *
 * GREEDY_STRING captures the free-text goal exactly as the user typed it.
 * Api-url/api-key/model arguments are ALSO greedy — URLs contain ':' and
 * '/' which Brigadier's string() rejects unquoted (the "trailing data at
 * position 24" error). Quoted input still works (quotes stripped by Brigadier).
 */
public final class HyFuseCommands {

    private HyFuseCommands() { }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(literal("hyfuse")
.then(literal("status").executes(ctx -> {
                        reply(ctx.getSource(), com.hyfuse.bridge.agent.HyFuseCommandCore.execute("status"));
                        return 1;
                    }))
.then(literal("set")
.then(literal("api-url")
.then(argument("url", StringArgumentType.greedyString())
                                            .executes(ctx -> {
                                                reply(ctx.getSource(), com.hyfuse.bridge.agent.HyFuseCommandCore.execute(
                                                        "set api-url " + StringArgumentType.getString(ctx, "url")));
                                                return 1;
                                            })))
.then(literal("api-key")
.then(argument("key", StringArgumentType.greedyString())
                                            .executes(ctx -> {
                                                reply(ctx.getSource(), com.hyfuse.bridge.agent.HyFuseCommandCore.execute(
                                                        "set api-key " + StringArgumentType.getString(ctx, "key")));
                                                return 1;
                                            })))
.then(literal("model")
.then(argument("model", StringArgumentType.greedyString())
                                            .executes(ctx -> {
                                                reply(ctx.getSource(), com.hyfuse.bridge.agent.HyFuseCommandCore.execute(
                                                        "set model " + StringArgumentType.getString(ctx, "model")));
                                                return 1;
                                            })))
.then(literal("goal")
.then(argument("goal", StringArgumentType.greedyString())
                                            .executes(ctx -> {
                                                reply(ctx.getSource(), com.hyfuse.bridge.agent.HyFuseCommandCore.execute(
                                                        "set goal " + StringArgumentType.getString(ctx, "goal")));
                                                return 1;
                                            })))
.then(literal("agent_mode")
.then(argument("mode", StringArgumentType.string())
                                            .executes(ctx -> {
                                                reply(ctx.getSource(), com.hyfuse.bridge.agent.HyFuseCommandCore.execute(
                                                        "set agent_mode " + StringArgumentType.getString(ctx, "mode")));
                                                return 1;
                                            }))))
.then(literal("reset")
.then(argument("what", StringArgumentType.string())
                                    .suggests((ctx, builder) -> {
                                        for (String s: new String[]{"intelligence", "memory", "config", "all"}) {
                                            builder.suggest(s);
                                        }
                                        return builder.buildFuture();
                                    })
                                    .executes(ctx -> {
                                        reply(ctx.getSource(), com.hyfuse.bridge.agent.HyFuseCommandCore.execute(
                                                "reset " + StringArgumentType.getString(ctx, "what")));
                                        return 1;
                                    }))));
        });
    }

    /** Feedback as local chat lines (multi-line split; never sent to server). */
    private static void reply(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source,
                              String message) {
        for (String line: message.split("\\n")) {
            source.sendFeedback(Component.literal(line));
        }
    }
}
