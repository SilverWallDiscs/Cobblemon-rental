package dev.silver.pokemonrental;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class PokemonRentalMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("PokemonRental");
    private static RentalEngine engine;
    private int ticks;

    @Override public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(this::init);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> engine = null);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (engine != null && ++ticks % 200 == 0) engine.tick();
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (engine != null && entity instanceof PokemonEntity pokemonEntity &&
                    engine.shouldDiscardTemporaryEntity(pokemonEntity.getPokemon().getUuid())) {
                entity.discard();
            }
        });
        ServerPlayConnectionEvents.JOIN.register((handler,sender,server) -> {
            server.execute(() -> { if (engine!=null) engine.onLogin(handler.player); });
        });
        CommandRegistrationCallback.EVENT.register((dispatcher,registryAccess,environment) -> {
            dispatcher.register(literal("pokerental")
                .executes(ctx -> { ServerPlayer p=ctx.getSource().getPlayerOrException(); if(engine!=null) RentalMenus.market(p,engine,0); return 1; })
                .then(argument("slot",IntegerArgumentType.integer(1,6)).executes(ctx -> {
                    ServerPlayer p=ctx.getSource().getPlayerOrException();
                    if(engine!=null) RentalMenus.setup(p,engine,IntegerArgumentType.getInteger(ctx,"slot")); return 1;
                }))
                .then(literal("mine").executes(ctx -> { ServerPlayer p=ctx.getSource().getPlayerOrException(); if(engine!=null) RentalMenus.owned(p,engine,0); return 1; }))
                .then(literal("rented").executes(ctx -> { ServerPlayer p=ctx.getSource().getPlayerOrException(); if(engine!=null) RentalMenus.rented(p,engine,0); return 1; }))
                .then(literal("recover").executes(ctx -> { ServerPlayer p=ctx.getSource().getPlayerOrException(); if(engine!=null) RentalMenus.recover(p,engine,0); return 1; }))
            );
        });
        LOGGER.info("Pokémon Rental initialized (server-side). Source build: 0.2.1");
    }
    private void init(MinecraftServer server) {
        try {
            RentalRepository database = new RentalRepository(server.getWorldPath(LevelResource.ROOT));
            RentalConfig config = RentalConfig.load();
            engine = new RentalEngine(server,database,config);
            engine.validateEconomy(); // Never allow new listings with a missing backend/currency.
            engine.tick();
            LOGGER.info("Pokémon Rental loaded {} escrow records.",database.all().size());
        } catch(Exception e) {
            engine = null;
            LOGGER.error("Pokémon Rental disabled: escrow store unavailable. NEVER delete the originals folder to fix this!",e);
        }
    }
}
