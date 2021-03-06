package WolfShotz.Wyrmroost.entities.dragon;

import WolfShotz.Wyrmroost.WRConfig;
import WolfShotz.Wyrmroost.Wyrmroost;
import WolfShotz.Wyrmroost.client.render.RenderEvents;
import WolfShotz.Wyrmroost.client.screen.StaffScreen;
import WolfShotz.Wyrmroost.containers.DragonInvContainer;
import WolfShotz.Wyrmroost.entities.dragon.helpers.DragonBodyController;
import WolfShotz.Wyrmroost.entities.dragon.helpers.DragonInvHandler;
import WolfShotz.Wyrmroost.entities.dragonegg.DragonEggProperties;
import WolfShotz.Wyrmroost.entities.util.Animation;
import WolfShotz.Wyrmroost.entities.util.EntityDataEntry;
import WolfShotz.Wyrmroost.entities.util.IAnimatedEntity;
import WolfShotz.Wyrmroost.items.CustomSpawnEggItem;
import WolfShotz.Wyrmroost.items.DragonEggItem;
import WolfShotz.Wyrmroost.items.staff.StaffAction;
import WolfShotz.Wyrmroost.registry.WREntities;
import WolfShotz.Wyrmroost.util.Mafs;
import WolfShotz.Wyrmroost.util.TickFloat;
import com.google.common.collect.Sets;
import net.minecraft.entity.*;
import net.minecraft.entity.ai.attributes.IAttribute;
import net.minecraft.entity.ai.attributes.RangedAttribute;
import net.minecraft.entity.ai.controller.BodyController;
import net.minecraft.entity.ai.goal.SitGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.AbstractArrowEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.SpectralArrowEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.particles.ItemParticleData;
import net.minecraft.particles.ParticleTypes;
import net.minecraft.pathfinding.FlyingPathNavigator;
import net.minecraft.pathfinding.GroundPathNavigator;
import net.minecraft.util.DamageSource;
import net.minecraft.util.Hand;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.event.ForgeEventFactory;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static net.minecraft.entity.SharedMonsterAttributes.FLYING_SPEED;
import static net.minecraft.entity.SharedMonsterAttributes.MOVEMENT_SPEED;

/**
 * Created by WolfShotz 7/10/19 - 21:36
 * This is where the magic happens. Here be our Dragons!
 */
public abstract class AbstractDragonEntity extends TameableEntity implements IAnimatedEntity
{
    public static final IAttribute PROJECTILE_DAMAGE = new RangedAttribute(null, "generic.projectileDamage", 2d, 0, 2048d);

    // Common Data Parameters
    public static final DataParameter<Boolean> GENDER = EntityDataManager.createKey(AbstractDragonEntity.class, DataSerializers.BOOLEAN);
    public static final DataParameter<Boolean> FLYING = EntityDataManager.createKey(AbstractDragonEntity.class, DataSerializers.BOOLEAN);
    public static final DataParameter<Boolean> SLEEPING = EntityDataManager.createKey(AbstractDragonEntity.class, DataSerializers.BOOLEAN);
    public static final DataParameter<Integer> VARIANT = EntityDataManager.createKey(AbstractDragonEntity.class, DataSerializers.VARINT);
    public static final DataParameter<Optional<BlockPos>> HOME_POS = EntityDataManager.createKey(AbstractDragonEntity.class, DataSerializers.OPTIONAL_BLOCK_POS);

    public final Set<String> immunes = Sets.newHashSet();
    public final Set<EntityDataEntry<?>> dataEntries = Sets.newHashSet();
    public final Optional<DragonInvHandler> invHandler;
    public final TickFloat sleepTimer = new TickFloat().setLimit(0, 1);
    public DragonEggProperties eggProperties;
    public Animation animation = NO_ANIMATION;
    public int sleepCooldown;
    public int animationTick;

    public AbstractDragonEntity(EntityType<? extends AbstractDragonEntity> dragon, World world)
    {
        super(dragon, world);

        invHandler = Optional.ofNullable(createInv());
        eggProperties = createEggProperties();
        stepHeight = 1;

        registerDataEntry("Sleeping", EntityDataEntry.BOOLEAN, SLEEPING, false);
        registerDataEntry("HomePos", EntityDataEntry.BLOCK_POS.optional(), HOME_POS, Optional.empty());
        invHandler.ifPresent(i -> registerDataEntry("Inv", EntityDataEntry.COMPOUND, i::serializeNBT, i::deserializeNBT));

        sleepTimer.set(isSleeping()? 1 : 0);
        setTamed(false);
    }

    @Override
    protected void registerGoals()
    {
        goalSelector.addGoal(1, new SwimGoal(this));
        goalSelector.addGoal(2, sitGoal = new SitGoal(this));
    }

    @Override
    protected BodyController createBodyController() { return new DragonBodyController(this); }

    // ================================
    //           Entity Data
    // ================================

    @Override
    protected void registerData()
    {
        super.registerData();
        dataManager.register(FLYING, false);
    }

    @Override
    public void writeAdditional(CompoundNBT nbt)
    {
        super.writeAdditional(nbt);
        dataEntries.forEach(e -> e.write(nbt));
    }

    @Override
    public void readAdditional(CompoundNBT nbt)
    {
        super.readAdditional(nbt);
        dataEntries.forEach(e -> e.read(nbt));
    }

    public <T> void registerDataEntry(String key, EntityDataEntry.SerializerType<T> type, Supplier<T> write, Consumer<T> read)
    {
        if (!world.isRemote) dataEntries.add(new EntityDataEntry<>(key, type, write, read));
    }

    public <T> void registerDataEntry(String key, EntityDataEntry.SerializerType<T> type, DataParameter<T> param, T value)
    {
        dataManager.register(param, value);
        registerDataEntry(key, type, () -> dataManager.get(param), v -> dataManager.set(param, v));
    }

    public void registerVariantData(int variants, boolean hasSpecial)
    {
        int chance = getSpecialChances();
        if (hasSpecial && chance != 0 && getRNG().nextInt(chance) == 0) variants = -1;
        else if (variants != 0) variants = getRNG().nextInt(variants);
        registerDataEntry("Variant", EntityDataEntry.INTEGER, VARIANT, variants);
    }

    public int getVariant()
    {
        try { return dataManager.get(VARIANT); }
        catch (NullPointerException ignore) { return 0; }
    }

    public void setVariant(int variant) { dataManager.set(VARIANT, variant); }

    public boolean isSpecial()
    {
        try { return dataManager.get(VARIANT) < 0; }
        catch (NullPointerException ignore) { return false; }
    }

    public void setSpecial() { setVariant(-1); }

    public int getSpecialChances() { return rand.nextInt(400) + 100; }

    public boolean isMale()
    {
        try { return dataManager.get(GENDER); }
        catch (NullPointerException ignore) { return true; }
    }

    public void setGender(boolean sex) { dataManager.set(GENDER, sex); }

    public boolean isFlying() { return dataManager.get(FLYING); }

    public void setFlying(boolean fly)
    {
        if (isFlying() == fly) return;
        dataManager.set(FLYING, fly);
        if (fly && canFly() && liftOff()) navigator = new FlyingPathNavigator(this, world);
        else navigator = new GroundPathNavigator(this, world);
    }

    @Override
    public boolean isSleeping() { return dataManager.get(SLEEPING); }

    public void setSleeping(boolean sleep)
    {
        if (isSleeping() == sleep) return;

        dataManager.set(SLEEPING, sleep);
        if (!world.isRemote)
        {
            clearAI();
            if (!sleep) this.sleepCooldown = 350;
        }
    }

    @Override
    public void setSitting(boolean sitting)
    {
        setSleeping(false);
        if (!world.isRemote)
        {
            sitGoal.setSitting(sitting);
            clearAI();
        }
        super.setSitting(sitting);
    }

    public DragonInvHandler getInvHandler()
    {
        return invHandler.orElseThrow(() -> new NoSuchElementException("This boi doesn't have an inventory wtf are u doing"));
    }

    public DragonInvHandler createInv() { return null; }

    // ================================

    @Override
    public void tick()
    {
        super.tick();

        if (getAnimation() != NO_ANIMATION)
        {
            ++animationTick;
            if (animationTick >= animation.getDuration()) setAnimation(NO_ANIMATION);
        }
    }

    @Override
    public void livingTick()
    {
        super.livingTick();

        if (isSleeping() && getHomePos().isPresent() && isWithinHomeDistanceCurrentPosition() && getRNG().nextInt(100) == 0)
            heal(0.5f);

        if (isServerWorld())
        {
            // uhh so were falling, we should probably start flying
            boolean flying = shouldFly();
            if (flying != isFlying()) setFlying(flying);

            handleSleep();
        }
        else
        {
            if (isSpecial()) doSpecialEffects();
        }
    }


    /**
     * Not to be confused with {@link #updatePassenger(Entity)}, as this is called when were riding something
     */
    @Override
    public void updateRidden()
    {
        super.updateRidden();

        Entity entity = getRidingEntity();

        if (entity == null || !entity.isAlive())
        {
            stopRiding();
            return;
        }

        setMotion(Vec3d.ZERO);
        clearAI();

        if (entity instanceof PlayerEntity)
        {
            PlayerEntity player = (PlayerEntity) entity;

            int index = player.getPassengers().indexOf(this);
            if ((player.isSneaking() && !player.abilities.isFlying) || isInWater() || index > 2)
            {
                stopRiding();
                return;
            }

            prevRotationPitch = rotationPitch = player.rotationPitch / 2;
            rotationYawHead = renderYawOffset = prevRotationYaw = rotationYaw = player.rotationYaw;
            setRotation(player.rotationYawHead, rotationPitch);

            Vec3d vec3d = getRidingPosOffset(index);
            if (player.isElytraFlying())
            {
                if (!canFly())
                {
                    stopRiding();
                    return;
                }

                vec3d = vec3d.scale(1.5);
                setFlying(true);
            }
            Vec3d pos = Mafs.getYawVec(player.renderYawOffset, vec3d.x, vec3d.z).add(player.getPosX(), player.getPosY() + vec3d.y, player.getPosZ());
            setPosition(pos.x, pos.y, pos.z);
        }
    }

    public Vec3d getRidingPosOffset(int passengerIndex)
    {
        double x = getWidth() * 0.5d + getRidingEntity().getWidth() * 0.5d;
        switch (passengerIndex)
        {
            default:
            case 0:
                return new Vec3d(0, 1.81, 0);
            case 1:
                return new Vec3d(x, 1.38d, 0);
            case 2:
                return new Vec3d(-x, 1.38d, 0);
        }
    }

    /**
     * Not to be confused with {@link #updateRidden()}, as this is called when were being ridden by something
     */
    @Override
    public void updatePassenger(Entity passenger)
    {
        if (isPassenger(passenger))
        {
            Vec3d offset = getPassengerPosOffset(passenger, getPassengers().indexOf(passenger));
            Vec3d pos = Mafs.getYawVec(renderYawOffset, offset.x, offset.z).add(getPosX(), getPosY() + offset.y + passenger.getYOffset(), getPosZ());
            passenger.setPosition(pos.x, pos.y, pos.z);
        }
    }

    public Vec3d getPassengerPosOffset(Entity entity, int index) { return new Vec3d(0, getMountedYOffset(), 0); }

    public boolean playerInteraction(PlayerEntity player, Hand hand, ItemStack stack)
    {
        if (stack.interactWithEntity(player, this, hand)) return true;

        if (isOwner(player) && player.isSneaking() && !isFlying())
        {
            setSitting(!isSitting());
            return true;
        }

        if (isTamed())
        {
            if (isBreedingItem(stack))
            {
                if (!world.isRemote && getGrowingAge() == 0 && canBreed())
                {
                    eat(stack);
                    setInLove(player);
                }
                return true;
            }

            if (isFoodItem(stack))
            {
                boolean flag = getHealth() < getMaxHealth();
                if (isChild())
                {
                    ageUp((int) ((-getGrowingAge() / 20) * 0.1F), true);
                    flag = true;
                }

                if (flag)
                {
                    eat(stack);
                    return true;
                }
            }
        }

        return false;
    }

    // Override to make processInteract way less annoying
    @Override
    public boolean processInteract(PlayerEntity player, Hand hand)
    {
        if (playerInteraction(player, hand, player.getHeldItem(hand)))
        {
            setSleeping(false);
            return true;
        }
        return false;
    }

    @Override
    public void travel(Vec3d vec3d)
    {
        float speed = isFlying()? (float) getAttribute(FLYING_SPEED).getValue() : (float) getAttribute(MOVEMENT_SPEED).getValue() * 0.3f;

        if (canPassengerSteer()) // Were being controlled
        {
            LivingEntity entity = (LivingEntity) getControllingPassenger();
            double moveY = vec3d.y;
            double moveX = entity.moveStrafing;
            double moveZ = entity.moveForward;

            // rotate head to match driver. rotationYaw is handled relative to this.
            rotationYawHead = entity.rotationYawHead;
            rotationPitch = entity.rotationPitch * 0.5f;

            if (isFlying())
            {
                if (entity.moveForward != 0) moveY = entity.getLookVec().y * speed * 18;
                moveX = vec3d.x;
            }
            else if (entity.isJumping)
            {
                if (canFly()) setFlying(true);
                else jumpController.setJumping();
            }

            setAIMoveSpeed(speed);
            vec3d = new Vec3d(moveX, moveY, moveZ);
        }

        if (isFlying())
        {
            // Move relative to rotationYaw
            moveRelative(speed, vec3d);
            move(MoverType.SELF, getMotion());
            setMotion(getMotion().scale(0.91f));

            if (getMotion().lengthSquared() < 0.04f) // Not Moving, just hover
                setMotion(getMotion().add(0, Math.cos(ticksExisted * 0.1f) * 0.02f, 0));

            float limbSpeed = 0.4f;
            float amount = 1f;
            if (getPosY() - prevPosY < -0.2f)
            {
                amount = 0f;
                limbSpeed = 0.2f;
            }

            prevLimbSwingAmount = limbSwingAmount;
            limbSwingAmount += (amount - limbSwingAmount) * limbSpeed;
            limbSwing += limbSwingAmount;

            return;
        }

        super.travel(vec3d);
    }

    public boolean shouldFly() { return canFly() && getAltitude() > getFlightThreshold(); }

    @Override
    public void notifyDataManagerChange(DataParameter<?> key)
    {
        super.notifyDataManagerChange(key);
        if (key == SLEEPING || key == FLYING || key == TAMED) recalculateSize();
    }

    public ItemStack getStackInSlot(int slot)
    {
        return invHandler.map(i -> i.getStackInSlot(slot)).orElse(ItemStack.EMPTY);
    }

    /**
     * It is VERY important to be careful when using this.
     * It is VERY sidedness sensitive. If not done correctly, it can result in the loss of items! <P>
     * {@code if (!world.isReomote) setStackInSlot(...)}
     *
     * @param slot
     * @param stack
     */
    public void setStackInSlot(int slot, ItemStack stack) { invHandler.ifPresent(i -> i.setStackInSlot(slot, stack)); }

    public void attackInFront(double radius)
    {
        AxisAlignedBB size = getBoundingBox();
        AxisAlignedBB aabb = size.offset(Mafs.getYawVec(renderYawOffset, 0, size.getXSize())).grow(radius);
        attackInAABB(aabb);
    }

    public void attackInAABB(AxisAlignedBB aabb)
    {
        List<LivingEntity> livingEntities = world.getEntitiesWithinAABB(LivingEntity.class, aabb, found -> found != this && getPassengers().stream().noneMatch(found::equals));

        if (WRConfig.debugMode && world.isRemote) RenderEvents.queueRenderBox(aabb);
        if (livingEntities.isEmpty()) return;
        livingEntities.forEach(this::attackEntityAsMob);
    }

    @Override // Dont damage owners other pets!
    public boolean attackEntityAsMob(Entity entity)
    {
        if (entity == getOwner()) return false;
        if (entity instanceof TameableEntity && ((TameableEntity) entity).getOwner() == getOwner()) return false;

        return super.attackEntityAsMob(entity);
    }

    @Override // We shouldnt be targetting pets...
    public boolean shouldAttackEntity(LivingEntity target, LivingEntity owner) { return !isOnSameTeam(target); }

    @Override
    public boolean attackEntityFrom(DamageSource source, float amount)
    {
        if (isImmuneToArrows())
        {
            Entity attackSource = source.getImmediateSource();
            if (attackSource instanceof AbstractArrowEntity)
            {
                EntityType<?> type = attackSource.getType();
                if (type == WREntities.BLUE_GEODE_ARROW.get()) amount *= 0.25f;
                else if (type == WREntities.RED_GEODE_ARROW.get()) amount *= 0.5f;
                else if (type == WREntities.PURPLE_GEODE_ARROW.get()) amount *= 0.75f;
                else if (attackSource instanceof ArrowEntity || attackSource instanceof SpectralArrowEntity)
                    return false;
            }
        }

        setSleeping(false);
        if (getOwner() != null) setSitting(false);
        return super.attackEntityFrom(source, amount);
    }

    public void doSpecialEffects()
    {
        if (ticksExisted % 25 == 0)
        {
            double x = getPosX() + getWidth() * (getRNG().nextGaussian() * 0.5d);
            double y = getPosY() + getHeight() * (getRNG().nextDouble());
            double z = getPosZ() + getWidth() * (getRNG().nextGaussian() * 0.5d);
            world.addParticle(ParticleTypes.END_ROD, x, y, z, 0, 0.05f, 0);
        }
    }

    @Override
    protected void dropInventory() { invHandler.ifPresent(i -> i.getStacks().forEach(this::entityDropItem)); }

    public void tryTeleportToOwner()
    {
        if (getOwner() == null) return;
        final int CONSTRAINT = (int) (getWidth() * 0.5) + 1;
        BlockPos pos = getOwner().getPosition();
        BlockPos.Mutable potentialPos = new BlockPos.Mutable();

        for (int x = -CONSTRAINT; x < CONSTRAINT; x++)
            for (int y = -2; y < 2; y++)
                for (int z = -CONSTRAINT; z < CONSTRAINT; z++)
                {
                    potentialPos.setPos(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
//                    if (getPosX() - potentialPos.getX() < 2 && getPosZ() - potentialPos.getZ() < 2) continue;
                    if (trySafeTeleport(potentialPos)) return;
                }
    }

    public boolean trySafeTeleport(BlockPos pos)
    {
        if (world.hasNoCollisions(this, getBoundingBox().offset(pos.subtract(getPosition()))))
        {
            setLocationAndAngles(pos.getX(), pos.getY(), pos.getZ(), rotationYaw, rotationPitch);
            return true;
        }
        return false;
    }

    @Override
    public BlockPos getHomePosition() { return getHomePos().orElse(BlockPos.ZERO); }

    public Optional<BlockPos> getHomePos() { return dataManager.get(HOME_POS); }

    public void setHomePos(@Nullable BlockPos pos) { setHomePos(Optional.ofNullable(pos)); }

    public void setHomePos(Optional<BlockPos> pos) { dataManager.set(HOME_POS, pos); }

    @Override
    public boolean detachHome() { return getHomePos().isPresent(); }

    @Override
    public float getMaximumHomeDistance() { return WRConfig.homeRadius; }

    @Override
    public void setHomePosAndDistance(BlockPos pos, int distance) { setHomePos(pos); }

    @Override
    public boolean isWithinHomeDistanceCurrentPosition() { return isWithinHomeDistanceFromPosition(getPosition()); }

    @Override
    public boolean isWithinHomeDistanceFromPosition(BlockPos pos)
    {
        return getHomePos().map(home -> home.distanceSq(pos) <= WRConfig.homeRadius * WRConfig.homeRadius).orElse(true);
    }

    public void setRotation(float yaw, float pitch)
    {
        this.rotationYaw = yaw % 360.0F;
        this.rotationPitch = pitch % 360.0F;
    }

    public double getAltitude()
    {
        BlockPos.Mutable pos = new BlockPos.Mutable(getPosition());

        // cap to the world void (y = 0)
        while (pos.getY() > 0 && !world.getBlockState(pos).getMaterial().isSolid()) pos.move(0, -1, 0);
        return getPosY() - pos.getY();
    }

    // overload because... WHY IS `World` A PARAMETER WTF THE FIELD IS LITERALLY PUBLIC
    public void eat(ItemStack stack) { onFoodEaten(world, stack); }

    @Override
    public ItemStack onFoodEaten(World world, ItemStack stack)
    {
        float max = getMaxHealth();
        if (getHealth() < max) heal(Math.max((int) max / 5, 4)); // Base healing on max health, minumum 2 hearts.

        if (world.isRemote)
        {
            Vec3d mouth = getApproximateMouthPos();
            for (int i = 0; i < 11; ++i)
            {
                Vec3d vec3d1 = new Vec3d(((double) rand.nextFloat() - 0.5D) * 0.1D, Math.random() * 0.1D + 0.1D, ((double) rand.nextFloat() - 0.5D) * 0.1D);
                vec3d1 = vec3d1.rotatePitch(-rotationPitch * (Mafs.PI / 180f));
                vec3d1 = vec3d1.rotateYaw(-rotationYaw * (Mafs.PI / 180f));
                world.addParticle(new ItemParticleData(ParticleTypes.ITEM, stack), mouth.x, mouth.y, mouth.z, vec3d1.x, vec3d1.y, vec3d1.z);
            }
        }

        return super.onFoodEaten(world, stack);
    }

    public boolean tame(boolean tame, @Nullable PlayerEntity tamer)
    {
        if (isTamed()) return true;
        if (world.isRemote) return false;
        if (tame && tamer != null && !ForgeEventFactory.onAnimalTame(this, tamer))
        {
            setTamedBy(tamer);
            navigator.clearPath();
            setAttackTarget(null);
            setHealth(getMaxHealth());
            world.setEntityState(this, (byte) 7);
            return true;
        }
        else world.setEntityState(this, (byte) 6);

        return false;
    }

    @Override
    public void heal(float healAmount)
    {
        super.heal(healAmount);

        if (world.isRemote)
        {
            for (int i = 0; i < getWidth() * 5; ++i)
            {
                double x = getPosX() + (getRNG().nextGaussian() * getWidth()) / 1.5d;
                double y = getPosY() + getRNG().nextDouble() * (getRNG().nextDouble() + 2d);
                double z = getPosZ() + (getRNG().nextGaussian() * getWidth()) / 1.5d;
                world.addParticle(ParticleTypes.HAPPY_VILLAGER, x, y, z, 0, 0, 0);
            }
        }
    }

    @Override
    public boolean canMateWith(AnimalEntity otherAnimal)
    {
        if (!super.canMateWith(otherAnimal)) return false;
        return !isSitting() && !((AbstractDragonEntity) otherAnimal).isSitting();
    }

    @Override
    public int getHorizontalFaceSpeed()
    {
        return isFlying()? 10 : super.getHorizontalFaceSpeed();
    }

    public boolean isRiding() { return getRidingEntity() != null; }

    @Nullable
    @Override
    public AgeableEntity createChild(AgeableEntity ageable)
    {
        ItemStack eggStack = DragonEggItem.createNew((EntityType<AbstractDragonEntity>) getType(), getEggProperties().getHatchTime());
        ItemEntity eggItem = new ItemEntity(world, getPosX(), getPosY(), getPosZ(), eggStack);

        eggItem.setMotion(0, getHeight() / 3, 0);
        world.addEntity(eggItem);

        return null;
    }

    public void handleSleep()
    {
        if (!isSleeping()
                && --sleepCooldown <= 0
                && !world.isDaytime()
                && (!isTamed() || (isSitting() && isWithinHomeDistanceCurrentPosition()))
                && isIdling()
                && !isInWaterOrBubbleColumn()
                && getRNG().nextInt(300) == 0) setSleeping(true);
        else if (isSleeping() && world.isDaytime() && getRNG().nextInt(150) == 0) setSleeping(false);
    }

    @Override
    protected void addPassenger(Entity passenger)
    {
        super.addPassenger(passenger);
        if (getControllingPassenger() == passenger)
        {
            clearAI();
            setSitting(false);
            setHomePos(BlockPos.ZERO);
        }
    }

    /**
     * Get the player potentially controlling this dragon
     * {@code null} if its not a player or no controller at all.
     */
    @Nullable
    public PlayerEntity getControllingPlayer()
    {
        Entity passenger = getControllingPassenger();
        if (passenger instanceof PlayerEntity) return (PlayerEntity) passenger;
        return null;
    }

    public void clearAI()
    {
        isJumping = false;
        navigator.clearPath();
        setAttackTarget(null);
        setMoveForward(0);
        setMoveVertical(0);
    }

    public boolean isIdling()
    {
        return getNavigator().noPath()
                && getAttackTarget() == null
                && !isBeingRidden()
                && !isInWaterOrBubbleColumn()
                && !isFlying();
    }

    /**
     * A universal getter for the position of the mouth on the dragon.
     * This is prone to be inaccurate, but can serve good enough for most things
     * If a more accurate position is needed, best to override and adjust accordingly.
     *
     * @return An approximate position of the mouth of the dragon
     */
    public Vec3d getApproximateMouthPos()
    {
        return Mafs.getYawVec(renderYawOffset, 0, (getWidth() / 2) + 0.5d).add(getPosX(), getPosY() + getEyeHeight() - 0.15d, getPosZ());
    }

    @Override
    public ItemStack getPickedResult(RayTraceResult target)
    {
        Optional<CustomSpawnEggItem> egg = CustomSpawnEggItem.EGG_TYPES.stream().filter(e -> getType().equals(e.type.get())).findFirst();
        return egg.map(ItemStack::new).orElse(ItemStack.EMPTY);
    }

    public List<LivingEntity> getEntitiesNearby(double radius, Predicate<LivingEntity> filter)
    {
        return world.getEntitiesWithinAABB(LivingEntity.class, getBoundingBox().grow(radius), filter.and(e -> e != this && !getPassengers().contains(e)));
    }

    public List<LivingEntity> getEntitiesNearby(double radius)
    {
        return world.getEntitiesWithinAABB(LivingEntity.class, getBoundingBox().grow(radius), e -> e != this && !getPassengers().contains(e));
    }

    @Override
    @SuppressWarnings("ConstantConditions")
    public boolean isOnSameTeam(Entity entity)
    {
        if (entity instanceof LivingEntity && isOwner(((LivingEntity) entity))) return true;
        if (entity instanceof TameableEntity && ((TameableEntity) entity).getOwner() == getOwner()) return true;
        if (entity.isOnScoreboardTeam(getTeam())) return true;
        return entity.getType().equals(getType());
    }

    public void addMotion(Vec3d vec3d) { setMotion(getMotion().add(vec3d)); }

    public void addMotion(double x, double y, double z) { setMotion(getMotion().add(x, y, z)); }

    public boolean isMoving()
    {
        double d0 = getPosX() - prevPosX;
        double d1 = getPosZ() - prevPosZ;
        return d0 * d0 + d1 * d1 > 2.5000003E-7d;
    }

    @Override
    public void playSound(SoundEvent soundIn, float volume, float pitch) { playSound(soundIn, volume, pitch, false); }

    public void playSound(SoundEvent sound, float volume, float pitch, boolean local)
    {
        if (isSilent()) return;

        volume *= getSoundVolume();
        pitch *= getSoundPitch();

        if (local) world.playSound(getPosX(), getPosY(), getPosZ(), sound, getSoundCategory(), volume, pitch, false);
        else world.playSound(null, getPosX(), getPosY(), getPosZ(), sound, getSoundCategory(), volume, pitch);
    }

    @Override
    public void playAmbientSound() { if (!isSleeping()) super.playAmbientSound(); }

    public void setImmune(DamageSource source) { immunes.add(source.getDamageType()); }

    @Override
    public boolean isInvulnerableTo(DamageSource source)
    {
        if (isRiding() && source == DamageSource.IN_WALL) return true;
        if (!immunes.isEmpty() && immunes.contains(source.getDamageType())) return true;
        return super.isInvulnerableTo(source);
    }

    @Override
    public boolean canBeCollidedWith() { return super.canBeCollidedWith() && !isRiding(); }

    @Override
    public boolean canPassengerSteer() // Only OWNERS can control their pets
    {
        Entity entity = getControllingPassenger();
        return entity instanceof LivingEntity && isOwner((LivingEntity) entity);
    }

    @Nullable
    @Override
    public Entity getControllingPassenger() { return this.getPassengers().isEmpty()? null : this.getPassengers().get(0); }

    @Override
    public boolean isOnLadder() { return false; }

    public void recievePassengerKeybind(int key, int modifiers) {}

    /**
     * Sort of misleading name. if this is true, then nothing else is ticked (goals, look, etc)
     * Do not perform any AI actions while: Not Sleeping; not being controlled.
     */
    @Override
    protected boolean isMovementBlocked() { return super.isMovementBlocked() || isSleeping() || getControllingPlayer() != null; }

    public boolean canFly() { return !isChild() && !getLeashed(); }

    /**
     * Get the motion this entity performs when jumping
     */
    @Override
    protected float getJumpUpwardsMotion()
    {
        if (canFly()) return (getJumpFactor() * 0.175f) * getHeight();
        else return super.getJumpUpwardsMotion();
    }

    public boolean liftOff()
    {
        if (!canFly()) return false;
        if (!onGround) return true; // We can't lift off the ground in the air...

        for (int i = 1; i < (getFlightThreshold() / 2.5f) + 1; ++i)
            if (world.getBlockState(getPosition().up((int) getHeight() + i)).getMaterial().blocksMovement())
                return false;
        setSitting(false);
        setSleeping(false);
        jump();

        return true;
    }

    @Override // Disable fall calculations if we can fly (fall damage etc.)
    public boolean onLivingFall(float distance, float damageMultiplier)
    {
        if (canFly()) return false;
        return super.onLivingFall(distance, damageMultiplier);
    }

    public double getFlightThreshold() { return getHeight(); }

    /**
     * todo make a forge patch to allow this to actually work
     */
    public void setMountCameraAngles(boolean backView) {}

    public boolean isImmuneToArrows() { return false; }

    public void addScreenInfo(StaffScreen screen)
    {
        screen.addAction(StaffAction.HOME_POS);
        screen.addAction(StaffAction.SIT);

        screen.toolTip.add("Owner: " + getOwner().getName().getUnformattedComponentText());
        screen.toolTip.add(String.format("Health: %s / %s", (int) getHealth(), (int) getMaxHealth()));
    }

    public void addContainerInfo(DragonInvContainer container)
    {
        container.buildPlayerSlots(container.playerInv, 17, 136);
    }

    public void onInvContentsChanged(int slot, ItemStack stack, boolean onLoad) {}

    @Override
    public EntitySize getSize(Pose poseIn)
    {
        EntitySize size = getType().getSize().scale(getRenderScale());
        if (isSitting() || isSleeping()) size = size.scale(1, 0.5f);
        return size;
    }

    @Override
    public boolean isBreedingItem(ItemStack stack) { return isFoodItem(stack); }

    public boolean isFoodItem(ItemStack stack)
    {
        if (getFoodItems() == null || getFoodItems().size() == 0) return false;
        if (stack.isEmpty()) return false;
        return getFoodItems().contains(stack.getItem());
    }

    public abstract Collection<Item> getFoodItems();

    public DragonEggProperties getEggProperties()
    {
        if (eggProperties == null) // This shouldn't happen, lazily fix it if it does tho.
        {
            Wyrmroost.LOG.warn("{} is missing dragon egg properties! Contact Mod Author. Using default values...", getType().getName().getUnformattedComponentText());
            eggProperties = new DragonEggProperties(2f, 2f, 12000);
        }
        return eggProperties;
    }

    public abstract DragonEggProperties createEggProperties();

    // ================================
    //        Entity Animation
    // ================================

    @Override
    public int getAnimationTick() { return animationTick; }

    @Override
    public void setAnimationTick(int tick) { animationTick = tick; }

    @Override
    public Animation getAnimation() { return animation; }

    @Override
    public void setAnimation(Animation animation)
    {
        if (animation == null) animation = NO_ANIMATION;
        setAnimationTick(0);
        this.animation = animation;
    }

    @Override
    public Animation[] getAnimations() { return new Animation[0]; }
}
