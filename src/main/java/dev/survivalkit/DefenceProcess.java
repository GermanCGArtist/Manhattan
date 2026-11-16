package dev.survivalkit;

import dev.midtown.api.EngineSelector;
import dev.midtown.api.EngineStatus;
import dev.midtown.api.GoalSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

import static dev.survivalkit.Config.cfg;

/** Defends against hostile mobs while a task runs. */
final class DefenceProcess extends Feature {
    private static final double RANGE = 6.0;        // blocks; start fighting when closer than this
    private static final double REACH = 3.6;        // blocks; hit reach of the weapon
    private static final int MAX_FIGHT = 20 * 25;   // gives up on a mob after 25 s (avoids chasing indefinitely)

    private LivingEntity target;
    private int fightTimer;
    private final Set<Integer> ignored = new HashSet<>();
    private final Set<Integer> fought = new HashSet<>();
    private boolean warnedWeapon;
    private boolean keysHeld;
    private boolean midtownPursuing;
    private int blockedTicks;
    private int strafeTicks;
    private int strafeDir = 1;

    /** Set by the script's defend(): fight everything near until nothing is left, helper on or off. */
    static volatile boolean forced;

    /** Hostile mobs worth fighting within this range (the same choice as the fight itself makes). */
    static int threats(Minecraft mc, LocalPlayer p, double range) {
        if (mc.level == null || p == null) return 0;
        int n = 0;
        for (net.minecraft.world.entity.monster.Monster m : mc.level.getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, p.getBoundingBox().inflate(range, 4, range))) {
            if (!m.isAlive() || m.distanceToSqr(p) > range * range) continue;
            if (m instanceof net.minecraft.world.entity.monster.warden.Warden || m instanceof net.minecraft.world.entity.boss.wither.WitherBoss) continue;
            if (m instanceof net.minecraft.world.entity.monster.piglin.Piglin && !m.isAggressive() && net.minecraft.world.entity.monster.piglin.PiglinAi.isWearingSafeArmor(p)) continue;
            if (m instanceof net.minecraft.world.entity.monster.EnderMan e && !e.isCreepy()) continue;
            if (m instanceof net.minecraft.world.entity.NeutralMob && !m.isAggressive()) continue;
            n++;
        }
        return n;
    }

    DefenceProcess() {
        super("SurvivalKit defence", 30);
    }

    @Override
    boolean busy() {
        return target != null;
    }

    @Override
    void tick() {
        if (!isActive()) {
            if (keysHeld) releaseKeys();
            return;
        }
        stepInternal();
    }

    private void releaseKeys() {
        if (!keysHeld) return;
        Minecraft mc = Minecraft.getInstance();
        Options o = mc.options;
        if (o != null) {
            o.keyUp.setDown(false);
            o.keyDown.setDown(false);
            o.keyLeft.setDown(false);
            o.keyRight.setDown(false);
            o.keySprint.setDown(false);
            o.keyJump.setDown(false);
        }
        keysHeld = false;
    }

    private void stopMidtownCombat() {
        releaseKeys();
        if (midtownPursuing) {
            EngineSelector.current().cancel();
            midtownPursuing = false;
        }
        blockedTicks = 0;
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
        if (target != null && (!target.isAlive() || target.isRemoved() || target.distanceTo(p) > RANGE + 4)) {
            target = null;
            stopMidtownCombat();
        }
        if (target == null) {
            target = pickTarget(mc, p);
            fightTimer = 0;
            warnedWeapon = false;
            blockedTicks = 0;
            if (target == null) {
                forced = false;
            }
        }
        return target != null;
    }

    private LivingEntity pickTarget(Minecraft mc, LocalPlayer p) {
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        AABB box = p.getBoundingBox().inflate(RANGE, 3, RANGE);
        for (LivingEntity e : mc.level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (e == p || !e.isAlive() || ignored.contains(e.getId())) continue;
            if (!(e instanceof Enemy)) continue;
            // creepers that are not hissing: don't start a fight with them, leave them be
            if (e instanceof Creeper c && c.getSwellDir() <= 0 && e.distanceTo(p) > 3.0) continue;
            double d = e.distanceTo(p);
            if (d < bestDist) { bestDist = d; best = e; }
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
            stopMidtownCombat();
            return;
        }

        equipWeapon(mc, p);

        Vec3 eye = p.getEyePosition();
        AABB bb = target.getBoundingBox();
        Vec3 close = new Vec3(Mth.clamp(eye.x, bb.minX, bb.maxX), Mth.clamp(eye.y, bb.minY, bb.maxY), Mth.clamp(eye.z, bb.minZ, bb.maxZ));
        double dist = eye.distanceTo(close);

        stepMidtown(mc, p, bb.getCenter(), eye, dist);
    }

    private void stepMidtown(Minecraft mc, LocalPlayer p, Vec3 aim, Vec3 eye, double dist) {
        if (target instanceof Creeper c && c.getSwellDir() > 0 && dist < 6.0) {
            if (p.horizontalCollision || midtownPursuing) {
                releaseKeys();
                if (!midtownPursuing || EngineSelector.current().status() == EngineStatus.IDLE) {
                    EngineSelector.current().goTo(GoalSpec.runAway(target.blockPosition(), 9.0));
                    midtownPursuing = true;
                }
            } else {
                aimSmooth(p, aim, eye);
                Options o = mc.options;
                o.keyUp.setDown(false);
                o.keyDown.setDown(true);
                o.keyLeft.setDown(false);
                o.keyRight.setDown(false);
                o.keySprint.setDown(false);
                o.keyJump.setDown(false);
                keysHeld = true;
            }
            return;
        }

        if (dist <= REACH + 1.5) {
            aimAndAttack(mc, p, aim, eye, dist);
        } else {
            aimSmooth(p, aim, eye);
        }

        if (dist > 2.6) {
            boolean los = p.hasLineOfSight(target);
            if (!los || p.horizontalCollision) blockedTicks++;
            else blockedTicks = 0;

            if (blockedTicks > 10 || dist > 6.5) {
                releaseKeys();
                if (!midtownPursuing || (fightTimer % 15 == 0 && EngineSelector.current().status() != EngineStatus.PLANNING)) {
                    EngineSelector.current().goTo(GoalSpec.near(target.blockPosition(), 2));
                    midtownPursuing = true;
                }
            } else {
                if (midtownPursuing) {
                    EngineSelector.current().cancel();
                    midtownPursuing = false;
                }
                Options o = mc.options;
                o.keyUp.setDown(true);
                o.keyDown.setDown(false);
                o.keyLeft.setDown(false);
                o.keyRight.setDown(false);
                o.keySprint.setDown(dist > 3.5 && !p.isInWater());
                o.keyJump.setDown(p.horizontalCollision && p.onGround());
                keysHeld = true;
            }
            return;
        }

        if (midtownPursuing) {
            EngineSelector.current().cancel();
            midtownPursuing = false;
        }
        blockedTicks = 0;
        if (++strafeTicks >= 25 || p.horizontalCollision) {
            strafeDir = -strafeDir;
            strafeTicks = 0;
        }
        Options o = mc.options;
        o.keyUp.setDown(dist > 2.4);
        o.keyDown.setDown(dist < 1.8);
        o.keyLeft.setDown(strafeDir < 0);
        o.keyRight.setDown(strafeDir > 0);
        o.keySprint.setDown(false);
        o.keyJump.setDown(false);
        keysHeld = true;
    }

    private static void aimSmooth(LocalPlayer p, Vec3 aim, Vec3 eye) {
        double dx = aim.x - eye.x, dy = aim.y - eye.y, dz = aim.z - eye.z;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        float dYaw = Mth.wrapDegrees(yaw - p.getYRot());
        float dPitch = pitch - p.getXRot();
        p.setYRot(p.getYRot() + Mth.clamp(dYaw, -40f, 40f));
        p.setXRot(Mth.clamp(p.getXRot() + Mth.clamp(dPitch, -30f, 30f), -90f, 90f));
    }

    private void aimAndAttack(Minecraft mc, LocalPlayer p, Vec3 aim, Vec3 eye, double dist) {
        double dx = aim.x - eye.x, dy = aim.y - eye.y, dz = aim.z - eye.z;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        float dYaw = Mth.wrapDegrees(yaw - p.getYRot());
        float dPitch = pitch - p.getXRot();
        p.setYRot(p.getYRot() + Mth.clamp(dYaw, -40f, 40f));
        p.setXRot(Mth.clamp(p.getXRot() + Mth.clamp(dPitch, -30f, 30f), -90f, 90f));

        boolean aimed = Math.abs(dYaw) < 12f && Math.abs(dPitch) < 12f;
        if (dist <= REACH && aimed && p.getAttackStrengthScale(0.5f) >= 1.0f && p.hasLineOfSight(target)) {
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

    /** Holds the best sword/axe. Only touches the hotbar so the inventory remains organized. */
    private void equipWeapon(Minecraft mc, LocalPlayer p) {
        int selected = p.getInventory().getSelectedSlot();
        int best = -1, bestScore = weaponScore(p.getInventory().getItem(selected));
        boolean anyWeapon = bestScore > 0;
        for (int i = 0; i < 36 && !anyWeapon; i++) anyWeapon = weaponScore(p.getInventory().getItem(i)) > 0;
        if (!anyWeapon) Util.warn("noweapon", "Fighting " + target.getName().getString() + " bare-handed: no usable sword or axe in the inventory.");
        int bestHotbar = -1, bestHotbarScore = bestScore;
        for (int i = 0; i < 36; i++) {
            int s = weaponScore(p.getInventory().getItem(i));
            if (s > bestScore) { bestScore = s; best = i; }
            if (i < 9 && s > bestHotbarScore) { bestHotbarScore = s; bestHotbar = i; }
        }
        if (bestHotbar >= 0 && bestHotbarScore >= bestScore) {
            p.getInventory().setSelectedSlot(bestHotbar);
            return;
        }
        if (best >= 9) {
            for (int h = 0; h < 9; h++) {
                if (p.getInventory().getItem(h).isEmpty()) {
                    Util.swapIntoHotbar(mc, p, best, h);
                    p.getInventory().setSelectedSlot(h);
                    return;
                }
            }
            if (bestHotbar >= 0) {
                p.getInventory().setSelectedSlot(bestHotbar);
            } else if (!warnedWeapon) {
                warnedWeapon = true;
                Util.msg("Defence: free a hotbar slot or put a sword/axe in the hotbar for a better weapon.");
            }
        }
    }

    @Override
    public void onLostControl() {
        target = null;
        forced = false;
        stopMidtownCombat();
    }
}
