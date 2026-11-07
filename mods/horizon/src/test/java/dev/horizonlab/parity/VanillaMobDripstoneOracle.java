package dev.horizonlab.parity;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.sounds.*;
import net.minecraft.tags.*;
import net.minecraft.world.damagesource.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.level.block.*;

/** Actual registered dripstone fallOn -> Pig/LivingEntity.causeFallDamage. */
public final class VanillaMobDripstoneOracle {
    static Object unsafe;
    static java.lang.reflect.Method allocate;
    static <T> T make(Class<T> type) throws Exception {
        return type.cast(allocate.invoke(unsafe, type));
    }
    static final class Sources extends DamageSources {
        DamageSource fall, stalagmite;
        Sources() { super(null); }
        @Override public DamageSource fall() { return fall; }
        @Override public DamageSource stalagmite() { return stalagmite; }
    }
    static final class Level extends net.minecraft.server.level.ServerLevel {
        Sources sources;
        Level() { super(null, null, null, null, null, null, false, 0L, List.of(), false); }
        @Override public DamageSources damageSources() { return sources; }
    }
    static final class Actor extends Pig {
        Sources sources;
        boolean immune;
        double passengerDistance;
        float passengerMultiplier, damage;
        DamageSource source;
        Actor() { super(null, null); }
        void configure(Level level) { setLevel(level); sources = level.sources; }
        @Override public DamageSources damageSources() { return sources; }
        @Override public boolean is(TagKey<EntityType<?>> tag) {
            if (tag != EntityTypeTags.FALL_DAMAGE_IMMUNE) throw new AssertionError(tag);
            return immune;
        }
        @Override public double getAttributeValue(Holder<Attribute> attribute) {
            if (attribute == Attributes.SAFE_FALL_DISTANCE) return 3.0;
            if (attribute == Attributes.FALL_DAMAGE_MULTIPLIER) return 1.0;
            throw new AssertionError(attribute);
        }
        @Override protected void propagateFallToPassengers(double distance, float multiplier, DamageSource source) {
            passengerDistance = distance; passengerMultiplier = multiplier; this.source = source;
        }
        @Override public Fallsounds getFallSounds() {
            return new Fallsounds(SoundEvents.GENERIC_SMALL_FALL, SoundEvents.GENERIC_BIG_FALL);
        }
        @Override public void playSound(SoundEvent sound, float volume, float pitch) {}
        @Override protected void playBlockFallSound() {}
        @Override public boolean hurtServer(net.minecraft.server.level.ServerLevel level, DamageSource source, float damage) {
            this.damage = damage; this.source = source; return true;
        }
    }
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var type = Class.forName("sun.misc.Unsafe");
        var field = type.getDeclaredField("theUnsafe"); field.setAccessible(true);
        unsafe = field.get(null); allocate = type.getMethod("allocateInstance", Class.class);
        var sources = make(Sources.class);
        sources.fall = make(DamageSource.class); sources.stalagmite = make(DamageSource.class);
        var level = make(Level.class); level.sources = sources;
        var rows = new JsonArray();
        for (var state : Blocks.POINTED_DRIPSTONE.getStateDefinition().getPossibleStates()) {
            for (double distance : new double[] {0.5, 1, 2.999999, 3, 4, 5, 8, 12}) {
                for (boolean immune : new boolean[] {false, true}) {
                    var entity = make(Actor.class); entity.configure(level); entity.immune = immune;
                    state.getBlock().fallOn(level, state, BlockPos.ZERO, entity, distance);
                    var row = new JsonObject();
                    row.addProperty("state", Block.getId(state)); row.addProperty("distance", distance);
                    row.addProperty("immune", immune); row.addProperty("damage", entity.damage);
                    row.addProperty("passenger_distance", entity.passengerDistance);
                    row.addProperty("passenger_multiplier", entity.passengerMultiplier);
                    row.addProperty("source", entity.source == null ? "none" : entity.source == sources.stalagmite ? "stalagmite" : "fall");
                    rows.add(row);
                }
            }
        }
        Files.writeString(Path.of(args[0]), new Gson().toJson(rows));
    }
}
