package dev.survivalkit;

import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalRunAway;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

import static dev.survivalkit.Config.cfg;

/**
 * Fights hostile mobs that come close. Server-friendly: it turns smoothly (limited degrees per tick), only swings
 * when the weapon cooldown is full and the mob is within normal reach and in line of sight.
 */
final class DefenceProcess extends Feature {
    private static final double SCAN_RANGE = 7.0;     // start fighting
    private static final double LOSE_RANGE = 12.0;    // stop caring
    private static final double REACH = 3.0;
    private static final int MAX_FIGHT = 20 * 25;     // give up on a mob that we cannot kill/reach in 25 s

    private final java.util.Set<Integer> fought = new java.util.HashSet<>();
    private Monster target;
    private int fightTimer;
    private boolean warnedWeapon;
    private final Set<Integer> ignored = new HashSet<>();

    /** Set by the script's defend(): fight everything near until nothing is left, helper on or off. */
    static volatile boolean forced;

    /** Hostile mobs worth fighting within this range (the same choice as the fight itself makes). */
    static int threats(Minecraft mc, LocalPlayer p, double range) {
        int n = 0;
        for (Monster m : mc.level.getEntitiesOfClass(Monster.class, p.getBoundingBox().inflate(range, 4, range))) {
            if (!m.isAlive() || m.distanceToSqr(p) > range * range) continue;
            if (m instanceof Warden || m instanceof WitherBoss) continue;
            if (m instanceof Piglin && !m.isAggressive() && PiglinAi.isWearingSafeArmor(p)) continue;
            if (m instanceof EnderMan e && !e.isCreepy()) continue;
            if (m instanceof NeutralMob && !m.isAggressive()) continue;
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
    boolean active() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if ((!Util.scriptOn("defence", cfg.defence) && !forced) || p == null || mc.level == null || mc.gameMode == null || p.isSleeping() || Util.guiOpen(p)) {
            target = null;
            return false;
        }
        if (target != null && (!target.isAlive() || target.distanceToSqr(p) > LOSE_RANGE * LOSE_RANGE)) target = null;
        if (target == null && (Util.mayAct() || forced)) {
            target = findThreat(mc, p);
            fightTimer = 0;
            if (target == null) forced = false;   // nothing (left) to fight
        }
        return target != null;
    }

    private Monster findThreat(Minecraft mc, LocalPlayer p) {
        if (ignored.size() > 200) ignored.clear();
        Monster best = null;
        double bestDist = SCAN_RANGE * SCAN_RANGE;
        for (Monster m : mc.level.getEntitiesOfClass(Monster.class, p.getBoundingBox().inflate(SCAN_RANGE, 4, SCAN_RANGE))) {
            if (!m.isAlive() || ignored.contains(m.getId())) continue;
            // never pick a fight we cannot win with a sword: leave these to the player
            if (m instanceof Warden || m instanceof WitherBoss) continue;
            // piglins are calm while the player wears gold: hitting one would anger the whole group
            // (piglin brutes ignore gold and always attack, so they stay on the list)
            if (m instanceof Piglin && !m.isAggressive() && PiglinAi.isWearingSafeArmor(p)) continue;
            // neutral mobs (zombified piglins, endermen) are only a threat once they are angry
            if (m instanceof EnderMan e && !e.isCreepy()) continue;
            if (m instanceof NeutralMob && !m.isAggressive()) continue;
            double d = m.distanceToSqr(p);
            if (d < bestDist) { bestDist = d; best = m; }
        }
        return best;
    }

    @Override
    PathingCommand step(boolean calcFailed, boolean isSafeToCancel) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (target == null || p == null) return defer();
        if (p.getHealth() <= 6) {
            Util.warn("fightlow", "Health is low (" + Math.round(p.getHealth()) + "/" + Math.round(p.getMaxHealth())
                    + ") while fighting " + target.getName().getString() + ".");
        }
        if (++fightTimer > MAX_FIGHT) {
            Util.warn("fightgiveup", "Could not kill or reach " + target.getName().getString() + " in 25 s - ignoring it.");
            ignored.add(target.getId());
            target = null;
            return defer();
        }

        equipWeapon(mc, p);

        Vec3 eye = p.getEyePosition();
        AABB bb = target.getBoundingBox();
        Vec3 close = new Vec3(Mth.clamp(eye.x, bb.minX, bb.maxX), Mth.clamp(eye.y, bb.minY, bb.maxY), Mth.clamp(eye.z, bb.minZ, bb.maxZ));
        double dist = eye.distanceTo(close);

        // a creeper that is about to blow: get away instead of trading hits
        if (target instanceof Creeper c && c.getSwellDir() > 0 && dist < 5.5) {
            return new PathingCommand(new GoalRunAway(9, target.blockPosition()), PathingCommandType.SET_GOAL_AND_PATH);
        }

        if (dist <= REACH + 0.5) aimAndAttack(mc, p, bb.getCenter(), eye, dist);

        if (dist > 2.4) {
            return new PathingCommand(new GoalNear(target.blockPosition(), 2), PathingCommandType.SET_GOAL_AND_PATH);
        }
        return pause();
    }

    private void aimAndAttack(Minecraft mc, LocalPlayer p, Vec3 aim, Vec3 eye, double dist) {
        double dx = aim.x - eye.x, dy = aim.y - eye.y, dz = aim.z - eye.z;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        float dYaw = Mth.wrapDegrees(yaw - p.getYRot());
        float dPitch = pitch - p.getXRot();
        // turn at most 40 / 30 degrees per tick instead of snapping
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

    /** Holds the best sword/axe. Only touches the hotbar so Baritone keeps the tools it expects there. */
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
        if (bestHotbar >= 0 && bestHotbarScore >= bestScore) {   // best weapon is already in the hotbar
            p.getInventory().setSelectedSlot(bestHotbar);
            return;
        }
        if (best >= 9) {   // best weapon sits in the main inventory: move it to an empty hotbar slot
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
    }
}
