package com.zoneraids.combat;

import com.mojang.logging.LogUtils;
import com.zoneraids.RaidConfig;
import com.zoneraids.survival.Lairs;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A bullet in the head kills a human NPC (bandits, soldiers, stalkers, traders: {@code headshotEntities}) outright,
 * unless a helmet stops it ({@code helmetSavesHeadshot}). Lair bosses only take TaCZ's normal headshot damage.
 * Human NPCs that die lie on the ground for {@code npcCorpseSeconds} before they vanish (see LivingEntityMixin).
 * <p>
 * Which hit is a headshot is TaCZ's own call (its EntityHurtByGunEvent, the same verdict that plays its headshot
 * sound and marker). Zone Raids does not compile against TaCZ, so the event is found by name; without it a
 * bullet that enters the target's box above the eyes counts.
 */
public final class NpcHeadshots {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String GUN_EVENT = "com.tacz.guns.api.event.common.EntityHurtByGunEvent$Pre";
    private static final ResourceLocation BULLET = new ResourceLocation("tacz", "bullet");
    /** How far below the eyes the head starts, when the bullet's path decides. */
    private static final double HEAD_BELOW_EYES = 0.25;
    /** Vanilla removes a dead mob after this many ticks. */
    private static final int VANILLA_DEATH_TICKS = 20;
    /** Entity -> game time of TaCZ's headshot verdict; the hurt that follows in the same tick reads it. */
    private static final Map<UUID, Long> headshots = new HashMap<>();
    private static boolean taczVerdict;
    private static boolean warnedInvoke;

    private NpcHeadshots() {
    }

    /** Listens to TaCZ's gun hit event when TaCZ is installed. */
    public static void register() {
        if (!ModList.get().isLoaded("tacz")) {
            return;
        }
        try {
            Class<?> type = Class.forName(GUN_EVENT);
            Method hurtEntity = type.getMethod("getHurtEntity");
            Method headShot = type.getMethod("isHeadShot");
            listen(type, event -> onGunHit(event, hurtEntity, headShot));
            taczVerdict = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.warn("Zone Raids: TaCZ headshot event not found ({}), headshots are judged by the bullet's path", e.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Event> void listen(Class<?> type, Consumer<T> listener) {
        // Lowest and not when cancelled: a hit another mod stopped is no headshot.
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, (Class<T>) type, listener);
    }

    private static void onGunHit(Event event, Method hurtEntity, Method headShot) {
        try {
            if (hurtEntity.invoke(event) instanceof LivingEntity target && !target.level().isClientSide()
                    && Boolean.TRUE.equals(headShot.invoke(event)) && diesToHeadshot(target)) {
                long now = target.level().getGameTime();
                headshots.values().removeIf(time -> time < now - 1);
                headshots.put(target.getUUID(), now);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!warnedInvoke) {
                warnedInvoke = true;
                LOGGER.warn("Zone Raids: cannot read TaCZ's headshot verdict, NPC headshots deal normal damage", e);
            }
        }
    }

    /** Low priority, after armor: a headshot raises the damage to at least what the NPC has left. */
    public static void onDamage(LivingDamageEvent event) {
        LivingEntity target = event.getEntity();
        if (target.level().isClientSide() || !RaidConfig.NPC_HEADSHOT_KILLS.get()) {
            return;
        }
        Long marked = headshots.remove(target.getUUID());
        boolean headshot = taczVerdict
                ? marked != null && marked == target.level().getGameTime()
                : diesToHeadshot(target) && bulletInHead(event.getSource(), target);
        if (!headshot || !(target.level() instanceof ServerLevel level)) {
            return;
        }
        Vec3 head = new Vec3(target.getX(), target.getEyeY(), target.getZ());
        if (stopsBullet(target.getItemBySlot(EquipmentSlot.HEAD)) && target.getRandom().nextDouble() < RaidConfig.HELMET_SAVES_HEADSHOT.get()) {
            // The helmet rings and the NPC takes the hit as an ordinary one.
            level.sendParticles(ParticleTypes.CRIT, head.x, head.y + 0.2, head.z, 8, 0.15, 0.1, 0.15, 0.2);
            level.playSound(null, head.x, head.y, head.z, SoundEvents.ANVIL_LAND, SoundSource.HOSTILE, 0.3F, 1.9F);
            return;
        }
        event.setAmount(Math.max(event.getAmount(), target.getHealth() + target.getAbsorptionAmount() + 1.0F));
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.REDSTONE_BLOCK.defaultBlockState()),
                head.x, head.y, head.z, 16, 0.12, 0.12, 0.12, 0.15);
    }

    /** How many ticks a dead entity lies before it is removed: longer than vanilla for human NPCs. */
    public static int corpseTicks(LivingEntity entity) {
        if (!isHumanNpc(entity)) {
            return VANILLA_DEATH_TICKS;
        }
        return Math.max(VANILLA_DEATH_TICKS, RaidConfig.NPC_CORPSE_SECONDS.get() * 20);
    }

    private static boolean diesToHeadshot(LivingEntity entity) {
        return isHumanNpc(entity) && !entity.getPersistentData().contains(Lairs.TAG_BOSS);
    }

    private static boolean isHumanNpc(LivingEntity entity) {
        if (entity instanceof Player) {
            return false;
        }
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        return id != null && RaidConfig.HEADSHOT_ENTITIES.get().contains(id.toString());
    }

    /** A helmet only counts if it gives armor: a balaclava or a cap does not stop a bullet. */
    private static boolean stopsBullet(ItemStack helmet) {
        return !helmet.isEmpty() && helmet.getAttributeModifiers(EquipmentSlot.HEAD).get(Attributes.ARMOR).stream()
                .anyMatch(modifier -> modifier.getAmount() > 0);
    }

    /** Fallback without TaCZ's verdict: where the bullet's path of this tick enters the target's (slightly inflated) box. */
    private static boolean bulletInHead(DamageSource source, LivingEntity target) {
        Entity bullet = source.getDirectEntity();
        if (bullet == null || !BULLET.equals(ForgeRegistries.ENTITY_TYPES.getKey(bullet.getType()))) {
            return false;
        }
        AABB box = target.getBoundingBox().inflate(0.1);
        Vec3 motion = bullet.getDeltaMovement();
        Vec3 from = bullet.position().subtract(motion);
        Optional<Vec3> entry = box.contains(from) ? Optional.of(from) : box.clip(from, bullet.position().add(motion));
        return entry.isPresent() && entry.get().y >= target.getEyeY() - HEAD_BELOW_EYES;
    }
}
