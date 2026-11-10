package vn.dread;

import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.*;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.joml.Vector3f;

/** KẺ LỘT DA - quái vật riêng của mod. Ngoài đời: chạm vào là bị kéo xuống Địa Ngục. */
public class FlayedEntity extends HostileEntity {

    private static final DustParticleEffect BLOOD = new DustParticleEffect(new Vector3f(0.6f, 0.0f, 0.02f), 1.4f);

    public FlayedEntity(EntityType<? extends HostileEntity> type, World world) {
        super(type, world);
        this.experiencePoints = 30;
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return HostileEntity.createHostileAttributes()
            .add(EntityAttributes.GENERIC_MAX_HEALTH, 60.0)
            .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.30)
            .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 8.0)
            .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 48.0)
            .add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 0.6);
    }

    @Override
    protected void initGoals() {
        this.goalSelector.add(0, new SwimGoal(this));
        this.goalSelector.add(1, new MeleeAttackGoal(this, 1.15, false));
        this.goalSelector.add(2, new WanderAroundFarGoal(this, 0.8));
        this.goalSelector.add(3, new LookAtEntityGoal(this, PlayerEntity.class, 16.0f));
        this.goalSelector.add(4, new LookAroundGoal(this));
        this.targetSelector.add(0, new RevengeGoal(this));
        this.targetSelector.add(1, new ActiveTargetGoal<>(this, PlayerEntity.class, true));
    }

    /** Ngoài Overworld: chạm = bị lôi xuống Địa Ngục. Trong Địa Ngục: gây sát thương thật. */
    @Override
    public boolean tryAttack(Entity target) {
        if (!this.getWorld().isClient && target instanceof ServerPlayerEntity sp
            && this.getWorld().getRegistryKey() != HellRealm.HELL) {
            DreadMod.dragToHell(sp);
            return true;
        }
        return super.tryAttack(target);
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.getWorld().isClient && this.age % 6 == 0 && this.getWorld() instanceof ServerWorld sw) {
            sw.spawnParticles(BLOOD, getX(), getY() + 1.0, getZ(), 3, 0.3, 0.7, 0.3, 0.0); // máu nhỏ giọt
        }
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        boolean r = super.damage(source, amount);
        if (r && this.getWorld() instanceof ServerWorld sw)
            sw.spawnParticles(BLOOD, getX(), getY() + 1.2, getZ(), 20, 0.3, 0.5, 0.3, 0.05);
        return r;
    }

    @Override
    public void onDeath(DamageSource source) {
        if (this.getWorld() instanceof ServerWorld sw)
            sw.spawnParticles(BLOOD, getX(), getY() + 1.0, getZ(), 90, 0.5, 0.9, 0.5, 0.12);
        super.onDeath(source);
    }

    @Override protected SoundEvent getAmbientSound() { return SoundEvents.ENTITY_WARDEN_AMBIENT; }
    @Override protected SoundEvent getHurtSound(DamageSource s) { return SoundEvents.ENTITY_RAVAGER_HURT; }
    @Override protected SoundEvent getDeathSound() { return SoundEvents.ENTITY_RAVAGER_DEATH; }
    @Override protected void playStepSound(BlockPos pos, BlockState state) {
        this.playSound(SoundEvents.ENTITY_ZOMBIE_STEP, 0.2f, 0.6f);
    }
}
