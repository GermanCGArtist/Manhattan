package dev.survivalkit;

import dev.midtown.api.EngineSelector;
import dev.midtown.api.EngineStatus;
import dev.midtown.api.GoalSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static dev.survivalkit.Config.cfg;

/** Defends against hostile mobs while a task runs. */
final class DefenceProcess extends Feature {
    private static final double RANGE = 7.0;        // blocks; start fighting when closer than this
    private static final double REACH = 3.6;        // blocks; hit reach of the weapon
    private static final int MAX_FIGHT = 20 * 25;   // gives up on a mob after 25 s (avoids chasing indefinitely)

    private static double lastQueryRange = 8.0;
    private static final Set<Integer> ignored = new HashSet<>();
    /** Mobs left alone for a while (not for good): entity id -> game time until which they do not count. */
    private static final Map<Integer, Long> ignoredUntil = new HashMap<>();
    private static final double LEASH = 5.0;          // blocks it may walk away from where a fight began, for a mob that is not after it
    private static final int LEASH_IGNORE = 20 * 60;  // ticks a mob that was not worth following is left alone

    private LivingEntity target;
    private int fightTimer;
    private final Set<Integer> fought = new HashSet<>();
    private final Map<Integer, Integer> pursuitFailures = new HashMap<>();
    private boolean keysHeld;
    private boolean midtownPursuing;
    private int blockedTicks;
    private int noProgressTicks;
    private double bestDist = Double.MAX_VALUE;
    private BlockPos lastPursuitGoalPos;
    private BlockPos fightOrigin;
    private int strafeTicks;
    private int strafeDir = 1;

    /** Set by the script's defend(): fight everything near until nothing is left, helper on or off. */
    static volatile boolean forced;
    static volatile double forcedRange = RANGE;

    public static double getEffectiveRange() {
        return lastQueryRange;
    }

    /** Unified threat predicate used by both threat counting and target selection. */
    public static boolean isThreat(LocalPlayer p, LivingEntity e, double maxRange, boolean includeCalmCreeper) {
        if (p == null || e == null || e == p || !e.isAlive() || ignored.contains(e.getId()) || ignoredFor(p, e)) return false;
        if (e.distanceTo(p) > maxRange || Math.abs(e.getY() - p.getY()) > Math.max(4.0, maxRange * 0.6)) return false;
        if (e instanceof Warden || e instanceof WitherBoss) return false;
        if (e instanceof EnderMan em && !em.isCreepy()) return false;
        if (e instanceof Piglin piglin && !piglin.isAggressive() && PiglinAi.isWearingSafeArmor(p)) return false;
        if (e instanceof Creeper c && !includeCalmCreeper && c.getSwelling(1.0f) <= 0 && c.getSwellDir() <= 0) return false;
        if (e instanceof NeutralMob nm) {
            if (e instanceof EnderMan em) return em.isCreepy();
            boolean angry = nm.isAngry() || (e instanceof Mob mob && (mob.isAggressive() || mob.getTarget() == p));
            if (!angry) return false;
            return nm.getTarget() == null || nm.getTarget() == p;
        }
        return (e instanceof Monster || e instanceof Slime || e instanceof Phantom || e instanceof Hoglin) && relevant(p, e);
    }

    private static boolean targetsUs(LocalPlayer p, LivingEntity e) {
        return e instanceof Mob mob && mob.getTarget() == p;
    }

    /**
     * Is this mob worth leaving the work for? One that is after us, right next to us, or in plain sight and close.
     * A mob behind a wall or down a cave that has not noticed us is not: walking to it is how the bot ended up
     * wandering into caves for nothing. A script's defend() fights everything near, as before.
     */
    private static boolean relevant(LocalPlayer p, LivingEntity e) {
        if (forced || targetsUs(p, e)) return true;
        double d = e.distanceTo(p);
        if (d <= 3.5) return true;
        return d <= 6.0 && p.hasLineOfSight(e);
    }

    private static boolean ignoredFor(LocalPlayer p, LivingEntity e) {
        Long until = ignoredUntil.get(e.getId());
        if (until == null) return false;
        if (p.level().getGameTime() >= until) {
            ignoredUntil.remove(e.getId());
            return false;
        }
        return true;
    }

    /** Stops going after the target and leaves it alone for a minute (it may still come to us and then counts again). */
    private void dropTarget(LocalPlayer p) {
        if (target != null) {
            if (ignoredUntil.size() > 500) ignoredUntil.clear();
            ignoredUntil.put(target.getId(), p.level().getGameTime() + LEASH_IGNORE);
        }
        target = null;
        stopMidtownCombat();
    }

    /** Hostile mobs worth fighting within this range (the same choice as the fight itself makes). */
    static int threats(Minecraft mc, LocalPlayer p, double range) {
        lastQueryRange = Math.max(8.0, range);
        if (mc.level == null || p == null) return 0;
        int n = 0;
        AABB box = p.getBoundingBox().inflate(range, Math.max(4.0, range * 0.6), range);
        for (LivingEntity e : mc.level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (isThreat(p, e, range, false)) n++;
        }
        return n;
    }

    DefenceProcess() {
        super("SurvivalKit defence", 30);
    }

    void force(double range) {
        forcedRange = Math.max(12.0, range);
        forced = true;
    }

    @Override
    boolean busy() {
        return target != null;
    }

    @Override
    void tick() {
        if (!isActive()) {
            if (keysHeld) releaseKeys(Minecraft.getInstance());
            return;
        }
        stepInternal();
    }

    private static final String MOTOR_OWNER = "defence";

    private void releaseKeys(Minecraft mc) {
        if (!keysHeld && !dev.midtown.body.BodyController.INSTANCE.isOwnedBy(MOTOR_OWNER)) return;
        dev.midtown.body.BodyController.INSTANCE.releaseIfOwner(mc, MOTOR_OWNER);
        keysHeld = false;
    }

    private void stopMidtownCombat() {
        releaseKeys(Minecraft.getInstance());
        if (midtownPursuing) {
            EngineSelector.current().cancel();
            midtownPursuing = false;
        }
        blockedTicks = 0;
        noProgressTicks = 0;
        bestDist = Double.MAX_VALUE;
        lastPursuitGoalPos = null;
    }

    @Override
    boolean active() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if ((!Util.scriptOn("defence", cfg.defence) && !forced && !Recovery.waiting) || p == null || mc.level == null || mc.gameMode == null) {
            if (target != null) { target = null; stopMidtownCombat(); }
            return false;
        }
        if (!Util.mayAct() || p.isSleeping() || Util.guiOpen(p)) return false;

        // Current target died or walked away: pick again
        double chaseRange = Math.max(RANGE + 6.0, forced ? forcedRange + 4.0 : 0.0);
        if (target != null && (!target.isAlive() || target.isRemoved() || ignored.contains(target.getId()) || target.distanceTo(p) > chaseRange)) {
            target = null;
            stopMidtownCombat();
        }
        if (target == null) {
            target = pickTarget(mc, p);
            fightOrigin = p.blockPosition();
            fightTimer = 0;
            blockedTicks = 0;
            noProgressTicks = 0;
            bestDist = Double.MAX_VALUE;
            lastPursuitGoalPos = null;
            if (target == null) {
                forced = false;
                forcedRange = RANGE;
            }
        }
        return target != null;
    }

    private LivingEntity pickTarget(Minecraft mc, LocalPlayer p) {
        double searchRange = forced ? Math.max(12.0, forcedRange) : RANGE;
        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;
        AABB box = p.getBoundingBox().inflate(searchRange, Math.max(4.0, searchRange * 0.6), searchRange);
        for (LivingEntity e : mc.level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (!isThreat(p, e, searchRange, false)) continue;
            double score = e.distanceTo(p);
            boolean targetingPlayer = e instanceof Mob mob && mob.getTarget() == p;
            if (p.hasLineOfSight(e) || targetingPlayer) {
                score -= 4.0;
            }
            if (score < bestScore) {
                bestScore = score;
                best = e;
            }
        }
        return best;
    }

    private void stepInternal() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (target == null || p == null) return;
        if (p.getHealth() <= 6) {
            Util.warn("fightlow", "Health is low (" + Math.round(p.getHealth()) + "/" + Math.round(p.getMaxHealth())
                    + ") while fighting " + target.getName().getString() + ".");
        }
        if (++fightTimer > MAX_FIGHT) {
            Util.warn("fightgiveup", "Could not kill or reach " + target.getName().getString() + " in 25 s - ignoring it.");
            ignored.add(target.getId());
            target = null;
            forced = false;
            forcedRange = RANGE;
            stopMidtownCombat();
            return;
        }

        // a mob that is not after us is not followed far from where the fight began (no wandering off into caves)
        if (!forced && !targetsUs(p, target) && fightOrigin != null
                && p.blockPosition().distSqr(fightOrigin) > LEASH * LEASH) {
            dropTarget(p);
            return;
        }

        equipWeapon(mc, p);

        Vec3 eye = p.getEyePosition();
        AABB bb = target.getBoundingBox();
        Vec3 close = new Vec3(Mth.clamp(eye.x, bb.minX, bb.maxX), Mth.clamp(eye.y, bb.minY, bb.maxY), Mth.clamp(eye.z, bb.minZ, bb.maxZ));
        double dist = eye.distanceTo(close);

        stepMidtown(mc, p, aimPoint(p, target), eye, dist);
    }

    private static Vec3 aimPoint(LocalPlayer p, LivingEntity target) {
        AABB bb = target.getBoundingBox();
        Vec3 center = bb.getCenter();
        double minY = bb.minY + 0.2;
        double maxY = bb.maxY - 0.1;
        double aimY = minY <= maxY ? Mth.clamp(p.getEyeY(), minY, maxY) : center.y;
        return new Vec3(center.x, aimY, center.z);
    }

    private void stepMidtown(Minecraft mc, LocalPlayer p, Vec3 aim, Vec3 eye, double dist) {
        if (target instanceof Creeper c && (c.getSwellDir() > 0 || c.getSwelling(1.0f) > 0) && dist < 6.0) {
            if (p.horizontalCollision || midtownPursuing) {
                if (!midtownPursuing) {
                    releaseKeys(mc);
                    midtownPursuing = true;
                    EngineSelector.current().goTo(GoalSpec.runAway(target.blockPosition(), 9.0));
                } else if (!EngineSelector.current().isBusy()) {
                    EngineSelector.current().goTo(GoalSpec.runAway(target.blockPosition(), 9.0));
                }
            } else {
                dev.midtown.body.BodyController.INSTANCE.acquire(mc, MOTOR_OWNER);
                aimSmooth(p, aim, eye, dist);
                dev.midtown.body.BodyController.INSTANCE.setMovementKeys(mc, false, true, false, false, false, false, false);
                keysHeld = true;
            }
            return;
        }

        boolean los = p.hasLineOfSight(target);
        double dy = Math.abs(target.getY() - p.getY());

        if (midtownPursuing) {
            if (dist <= 2.6 && los && dy <= 1.25) {
                EngineSelector.current().cancel();
                midtownPursuing = false;
                blockedTicks = 0;
                noProgressTicks = 0;
                bestDist = dist;
                lastPursuitGoalPos = null;
            } else if (EngineSelector.current().status() == EngineStatus.FAILED) {
                midtownPursuing = false;
                blockedTicks = 0;
                noProgressTicks = 0;
                bestDist = Double.MAX_VALUE;
                lastPursuitGoalPos = null;
                int fails = pursuitFailures.getOrDefault(target.getId(), 0) + 1;
                if (pursuitFailures.size() > 500) pursuitFailures.clear();
                pursuitFailures.put(target.getId(), fails);
                if (fails >= 2) {
                    Util.warn("fightgiveup", "Cannot path to " + target.getName().getString() + " - ignoring it.");
                    ignored.add(target.getId());
                    target = null;
                    forced = false;
                    forcedRange = RANGE;
                    stopMidtownCombat();
                }
                return;
            } else {
                equipWeapon(mc, p);
                if (dist <= 2.8) {
                    aimSmooth(p, aim, eye, dist);
                }
                tryAttackOnly(mc, p, target, dist, los);
                BlockPos goalPos = target.blockPosition();
                if (lastPursuitGoalPos == null
                        || (goalPos.distSqr(lastPursuitGoalPos) > 2.25 && fightTimer % 10 == 0)
                        || (!EngineSelector.current().isBusy() && fightTimer % 15 == 0)) {
                    EngineSelector.current().goTo(GoalSpec.near(goalPos, 1));
                    lastPursuitGoalPos = goalPos;
                }
                return;
            }
        }

        if (dist <= REACH + 1.5) {
            aimAndAttack(mc, p, aim, eye, dist, los);
        } else {
            aimSmooth(p, aim, eye, dist);
        }

        if (dist > 2.5) {
            if (dist < bestDist - 0.15) {
                bestDist = dist;
                noProgressTicks = 0;
            } else {
                noProgressTicks++;
            }
            if (!los || p.horizontalCollision || dy > 1.25) blockedTicks++;
            else blockedTicks = 0;

            if (blockedTicks > 8 || noProgressTicks > 12) {
                if (!forced && !targetsUs(p, target)) {   // cannot just walk to it and it is not after us: let it be
                    dropTarget(p);
                    return;
                }
                releaseKeys(mc);
                midtownPursuing = true;
                lastPursuitGoalPos = target.blockPosition();
                EngineSelector.current().goTo(GoalSpec.near(lastPursuitGoalPos, 1));
                return;
            }

            dev.midtown.body.BodyController.INSTANCE.acquire(mc, MOTOR_OWNER);
            dev.midtown.body.BodyController.INSTANCE.setMovementKeys(
                    mc,
                    true,
                    false,
                    false,
                    false,
                    p.horizontalCollision && p.onGround(),
                    false,
                    dist > 3.5 && !p.isInWater());
            keysHeld = true;
            return;
        }

        blockedTicks = 0;
        noProgressTicks = 0;
        bestDist = Double.MAX_VALUE;
        lastPursuitGoalPos = null;
        if (++strafeTicks >= 25 || p.horizontalCollision) {
            strafeDir = -strafeDir;
            strafeTicks = 0;
        }
        dev.midtown.body.BodyController.INSTANCE.acquire(mc, MOTOR_OWNER);
        dev.midtown.body.BodyController.INSTANCE.setMovementKeys(
                mc,
                dist > 2.4,
                dist < 1.8,
                strafeDir < 0,
                strafeDir > 0,
                false,
                false,
                false);
        keysHeld = true;
    }

    private static void aimSmooth(LocalPlayer p, Vec3 aim, Vec3 eye, double dist) {
        double dx = aim.x - eye.x, dy = aim.y - eye.y, dz = aim.z - eye.z;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        float maxYaw = dist <= 2.5 ? 90f : 65f;
        float maxPitch = dist <= 2.5 ? 90f : 45f;
        float dYaw = Mth.wrapDegrees(yaw - p.getYRot());
        float dPitch = pitch - p.getXRot();
        p.setYRot(p.getYRot() + Mth.clamp(dYaw, -maxYaw, maxYaw));
        p.setXRot(Mth.clamp(p.getXRot() + Mth.clamp(dPitch, -maxPitch, maxPitch), -90f, 90f));
    }

    private void aimAndAttack(Minecraft mc, LocalPlayer p, Vec3 aim, Vec3 eye, double dist, boolean los) {
        double dx = aim.x - eye.x, dy = aim.y - eye.y, dz = aim.z - eye.z;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        float maxYaw = dist <= 2.5 ? 90f : 65f;
        float maxPitch = dist <= 2.5 ? 90f : 45f;
        float dYaw = Mth.wrapDegrees(yaw - p.getYRot());
        float dPitch = pitch - p.getXRot();
        p.setYRot(p.getYRot() + Mth.clamp(dYaw, -maxYaw, maxYaw));
        p.setXRot(Mth.clamp(p.getXRot() + Mth.clamp(dPitch, -maxPitch, maxPitch), -90f, 90f));

        float remYaw = Mth.wrapDegrees(yaw - p.getYRot());
        float remPitch = pitch - p.getXRot();
        float tol = dist <= 2.0 ? 25f : 18f;
        boolean aimed = Math.abs(remYaw) < tol && Math.abs(remPitch) < tol;
        if (aimed) {
            tryAttackOnly(mc, p, target, dist, los);
        }
    }

    private void tryAttackOnly(Minecraft mc, LocalPlayer p, LivingEntity target, double dist, boolean los) {
        if (p.getAttackStrengthScale(0.5f) >= 0.9f && dist <= REACH && (los || dist <= 2.5)) {
            mc.gameMode.attack(p, target);
            if (fought.size() > 500) fought.clear();
            if (fought.add(target.getId())) Session.count("mobs fought");
            p.swing(InteractionHand.MAIN_HAND);
        }
    }

    // ------------------------------------------------------------------ weapon

    /** Higher is better; 0 = not a weapon. */
    private static int weaponScore(ItemStack st) {
        if (st.isEmpty() || Util.nearlyBroken(st)) return 0;
        String id = Util.pathOf(st);
        int base;
        if (id.endsWith("_sword")) base = 10;
        else if (id.endsWith("_axe") && !id.endsWith("pickaxe")) base = 6;
        else return 0;
        int material;
        if (id.startsWith("netherite")) material = 7;
        else if (id.startsWith("diamond")) material = 6;
        else if (id.startsWith("iron")) material = 5;
        else if (id.startsWith("copper")) material = 4;
        else if (id.startsWith("stone")) material = 3;
        else if (id.startsWith("golden")) material = 2;
        else material = 1;
        return base * 10 + material;
    }

    private static boolean isProtectedHotbarItem(ItemStack st) {
        if (st.isEmpty()) return false;
        if (st.has(DataComponents.FOOD)) return true;
        String id = Util.pathOf(st);
        return id.endsWith("_pickaxe")
                || (id.endsWith("_axe") && !id.endsWith("pickaxe"))
                || id.endsWith("_shovel");
    }

    /** Holds the best sword/axe, swapping from main inventory into a safe hotbar slot if needed. */
    private void equipWeapon(Minecraft mc, LocalPlayer p) {
        var inv = p.getInventory();
        int selected = inv.getSelectedSlot();
        int best = selected;
        int bestScore = weaponScore(inv.getItem(selected));
        for (int i = 0; i < 36; i++) {
            int s = weaponScore(inv.getItem(i));
            if (s > bestScore) {
                bestScore = s;
                best = i;
            }
        }
        if (bestScore <= 0) {
            Util.warn("noweapon", "Fighting " + target.getName().getString() + " bare-handed: no usable sword or axe in the inventory.");
            return;
        }
        if (best < 9) {
            inv.setSelectedSlot(best);
            return;
        }
        int swapHotbar = -1;
        for (int h = 0; h < 9; h++) {
            if (inv.getItem(h).isEmpty()) {
                swapHotbar = h;
                break;
            }
        }
        if (swapHotbar < 0) {
            for (int h = 0; h < 9; h++) {
                if (!isProtectedHotbarItem(inv.getItem(h))) {
                    swapHotbar = h;
                    break;
                }
            }
        }
        if (swapHotbar < 0) {
            swapHotbar = (selected + 1) % 9;
        }
        Util.swapIntoHotbar(mc, p, best, swapHotbar);
        inv.setSelectedSlot(swapHotbar);
    }

    @Override
    public void onLostControl() {
        target = null;
        forced = false;
        forcedRange = RANGE;
        stopMidtownCombat();
    }
}
