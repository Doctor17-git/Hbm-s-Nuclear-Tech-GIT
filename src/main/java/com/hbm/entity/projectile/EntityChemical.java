package com.hbm.entity.projectile;

import java.awt.Color;
import java.util.List;

import com.hbm.extprop.HbmLivingProps;
import com.hbm.handler.radiation.ChunkRadiationManager;
import com.hbm.inventory.fluid.FluidType;
import com.hbm.inventory.fluid.Fluids;
import com.hbm.inventory.fluid.trait.FT_Combustible;
import com.hbm.inventory.fluid.trait.FT_Corrosive;
import com.hbm.inventory.fluid.trait.FT_Flammable;
import com.hbm.inventory.fluid.trait.FT_VentRadiation;
import com.hbm.lib.ModDamageSource;
import com.hbm.main.MainRegistry;
import com.hbm.util.ArmorUtil;
import com.hbm.util.ContaminationUtil;
import com.hbm.util.ContaminationUtil.ContaminationType;
import com.hbm.util.ContaminationUtil.HazardType;
import com.hbm.util.EntityDamageUtil;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EntityDamageSourceIndirect;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

public class EntityChemical extends EntityThrowableNT {
	
	/*
	 * TYPE INFO:
	 * 
	 * if ANTIMATTER: ignore all other traits, become a gamme beam with no gravity
	 * if HOT: set fire and deal extra fire damage, scaling with the temperature
	 * if COLD: freeze, duration scaling with temperature, assuming COMBUSTIBLE does not apply
	 * if GAS: short range with the spread going up
	 * if EVAP: same as gas
	 * if LIQUID: if EVAP doesn't apply, create a narrow spray with long range affected by gravity
	 * if COMBUSTIBLE: auto-ignite
	 * if FLAMMABLE: if GAS or EVAP apply, do the same as COMBUSTIBLE, otherwise create a neutral spray that adds the "soaked" effect
	 * if CORROSIVE: apply extra acid damage, poison effect as well as armor degradation
	 */

	public EntityChemical(World world) {
		super(world);
		this.ignoreFrustumCheck = true;
	}

	public EntityChemical(World world, EntityLivingBase thrower) {
		super(world, thrower);
		this.ignoreFrustumCheck = true;
	}

	@Override
	protected void entityInit() {
		this.dataWatcher.addObject(10, new Integer(0));
	}
	
	public EntityChemical setFluid(FluidType fluid) {
		this.dataWatcher.updateObject(10, fluid.getID());
		return this;
	}
	
	public FluidType getType() {
		return Fluids.fromID(this.dataWatcher.getWatchableObjectInt(10));
	}
	
	@Override
	public void onUpdate() {
		
		if(!worldObj.isRemote) {
			
			if(this.ticksExisted > this.getMaxAge()) {
				this.setDead();
			}
			
			FluidType type = this.getType();
			
			if(type.hasTrait(Fluids.GASEOUS.getClass()) || type.hasTrait(Fluids.EVAP.getClass())) {

				double intensity = 1D - (double) this.ticksExisted / (double) this.getMaxAge();
				List<Entity> affected = worldObj.getEntitiesWithinAABBExcludingEntity(this.thrower, this.boundingBox.expand(intensity * 2.5, intensity * 2.5, intensity * 2.5));
				
				for(Entity e : affected) {
					this.affect(e, intensity);
				}
			}
			
		} else {
			
			ChemicalStyle style = getStyle();
			
			if(style == ChemicalStyle.LIQUID) {
				
				FluidType type = getType();
				Color color = new Color(type.getColor());
				
				NBTTagCompound data = new NBTTagCompound();
				data.setString("type", "vanillaExt");
				data.setString("mode", "colordust");
				data.setDouble("posX", posX);
				data.setDouble("posY", posY);
				data.setDouble("posZ", posZ);
				data.setDouble("mX", motionX + worldObj.rand.nextGaussian() * 0.05);
				data.setDouble("mY", motionY - 0.2 + worldObj.rand.nextGaussian() * 0.05);
				data.setDouble("mZ", motionZ + worldObj.rand.nextGaussian() * 0.05);
				data.setFloat("r", color.getRed() / 255F);
				data.setFloat("g", color.getGreen() / 255F);
				data.setFloat("b", color.getBlue() / 255F);
				MainRegistry.proxy.effectNT(data);
			}
			
			if(style == ChemicalStyle.BURNING) {
				
				double motion = Math.min(Vec3.createVectorHelper(motionX, motionY, motionZ).lengthVector(), 0.1);
				
				for(double d = 0; d < motion; d += 0.0625) {
					NBTTagCompound nbt = new NBTTagCompound();
					nbt.setString("type", "vanillaExt");
					nbt.setString("mode", "flame");
					nbt.setDouble("posX", (this.lastTickPosX - this.posX) * d + this.posX);
					nbt.setDouble("posY", (this.lastTickPosY - this.posY) * d + this.posY);
					nbt.setDouble("posZ", (this.lastTickPosZ - this.posZ) * d + this.posZ);
					MainRegistry.proxy.effectNT(nbt);
				}
			}
		}
		super.onUpdate();
	}
	
	protected void affect(Entity e, double intensity) {
		
		ChemicalStyle style = getStyle();
		FluidType type = getType();
		EntityLivingBase living = e instanceof EntityLivingBase ? (EntityLivingBase) e : null;
		
		if(style == ChemicalStyle.LIQUID || style == ChemicalStyle.BURNING) //ignore range penalty for liquids
			intensity = 1D;
		
		if(style == ChemicalStyle.AMAT) {
			if(living != null) {
				ContaminationUtil.contaminate(living, HazardType.RADIATION, ContaminationType.CREATIVE, 50F * (float) intensity);
				return;
			}
		}
		
		if(type.temperature >= 100) {
			EntityDamageUtil.attackEntityFromIgnoreIFrame(e, getDamage(ModDamageSource.s_boil), 5F + (type.temperature - 100) * 0.02F); //5 damage at 100°C with one extra damage every 50°C
			
			if(type.temperature >= 500) {
				e.setFire(10); //afterburn for 10 seconds
			}
		}
		
		if(style == ChemicalStyle.LIQUID || style == ChemicalStyle.GAS) {
			if(type.temperature < -20) {
				if(living != null) { //only living things are affected
					EntityDamageUtil.attackEntityFromIgnoreIFrame(e, getDamage(ModDamageSource.s_cryolator), 5F + (type.temperature + 20) * -0.05F); //5 damage at -20°C with one extra damage every -20°C
					living.addPotionEffect(new PotionEffect(Potion.moveSlowdown.id, 100, 2));
					living.addPotionEffect(new PotionEffect(Potion.digSlowdown.id, 100, 4));
				}
			}
			
			if(type.hasTrait(Fluids.DELICIOUS.getClass())) {
				if(living != null && living.isEntityAlive()) {
					living.heal(2F * (float) intensity);
				}
			}
		}
		
		if(style == ChemicalStyle.LIQUID) {
			
			if(type.hasTrait(FT_Flammable.class)) {
				if(living != null) {
					HbmLivingProps.setOil(living, 300); //doused in oil for 15 seconds
				}
			} else {
				
				if(type.temperature < 50) {
					e.extinguish(); //if it's a cold non-flammable liquid (that isn't burning), extinguish
				}
			}
		}
		
		if(style == ChemicalStyle.BURNING) {
			FT_Combustible trait = type.getTrait(FT_Combustible.class);
			EntityDamageUtil.attackEntityFromIgnoreIFrame(e, getDamage(ModDamageSource.s_flamethrower), 2F + (trait != null ? (trait.getCombustionEnergy() / 100_000F) : 0));
			e.setFire(5);
		}
		
		if(style == ChemicalStyle.GASFLAME) {
			FT_Flammable flammable = type.getTrait(FT_Flammable.class);
			FT_Combustible combustible = type.getTrait(FT_Combustible.class);
			
			float heat = Math.max(flammable != null ? flammable.getHeatEnergy() / 50_000F : 0, combustible != null ? combustible.getCombustionEnergy() / 100_000F : 0);
			heat *= intensity;
			EntityDamageUtil.attackEntityFromIgnoreIFrame(e, getDamage(ModDamageSource.s_flamethrower), (2F + heat) * (float) intensity);
			e.setFire((int) Math.ceil(5 * intensity));
		}
		
		if(type.hasTrait(FT_Corrosive.class)) {
			FT_Corrosive trait = type.getTrait(FT_Corrosive.class);
			EntityDamageUtil.attackEntityFromIgnoreIFrame(e, getDamage(ModDamageSource.s_acid), trait.getRating() / 20F);
			
			if(living != null) {
				for(int i = 0; i < 4; i++) {
					ArmorUtil.damageSuit(living, i, trait.getRating() / 5);
				}
			}
		}
		
		if(type.hasTrait(FT_VentRadiation.class)) {
			FT_VentRadiation trait = type.getTrait(FT_VentRadiation.class);
			if(living != null) {
				ContaminationUtil.contaminate(living, HazardType.RADIATION, ContaminationType.CREATIVE, trait.getRadPerMB() * 5);
			}
			ChunkRadiationManager.proxy.incrementRad(worldObj, (int) Math.floor(e.posX), (int) Math.floor(e.posY), (int) Math.floor(e.posZ), trait.getRadPerMB() * 5);
		}
	}
	
	protected DamageSource getDamage(String name) {
		
		if(thrower != null) {
			return new EntityDamageSourceIndirect(name, this, thrower);
		} else {
			return new DamageSource(name);
		}
	}

	@Override
	protected void onImpact(MovingObjectPosition mop) {
		
		if(!worldObj.isRemote) {
			
			if(mop.typeOfHit == mop.typeOfHit.ENTITY) {
				this.affect(mop.entityHit, 1D - (double) this.ticksExisted / (double) this.getMaxAge());
			}
			
			if(mop.typeOfHit == mop.typeOfHit.BLOCK) {
				
				FluidType type = getType();
				
				if(type.hasTrait(FT_VentRadiation.class)) {
					FT_VentRadiation trait = type.getTrait(FT_VentRadiation.class);
					ChunkRadiationManager.proxy.incrementRad(worldObj, mop.blockX, mop.blockY, mop.blockZ, trait.getRadPerMB() * 5);
				}
				
				ChemicalStyle style = getStyle();
				
				if(style == ChemicalStyle.BURNING || style == ChemicalStyle.GASFLAME) {
					int x = mop.blockX;
					int y = mop.blockY;
					int z = mop.blockZ;
					
					for(ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
						if(worldObj.getBlock(x + dir.offsetX, y + dir.offsetY, z + dir.offsetZ).isAir(worldObj, x + dir.offsetX, y + dir.offsetY, z + dir.offsetZ)) {
							worldObj.setBlock(x + dir.offsetX, y + dir.offsetY, z + dir.offsetZ, Blocks.fire);
						}
					}
				}
				
				this.setDead();
			}
		}
	}

	@Override
	protected float getAirDrag() {
		
		ChemicalStyle type = getStyle();

		if(type == ChemicalStyle.AMAT) return 1F;
		if(type == ChemicalStyle.GAS) return 0.95F;
		
		return 0.99F;
	}

	@Override
	protected float getWaterDrag() {
		
		ChemicalStyle type = getStyle();

		if(type == ChemicalStyle.AMAT) return 1F;
		if(type == ChemicalStyle.GAS) return 1F;
		
		return 0.8F;
	}
	
	public int getMaxAge() {
		
		switch(this.getStyle()) {
		case AMAT: return 100;
		case BURNING:return 600;
		case GAS: return 60;
		case GASFLAME: return 20;
		case LIQUID: return 600;
		}
		
		return 100;
	}

	@Override
	public double getGravityVelocity() {
		
		ChemicalStyle type = getStyle();

		if(type == ChemicalStyle.AMAT) return 0D;
		if(type == ChemicalStyle.GAS) return 0D;
		if(type == ChemicalStyle.GASFLAME) return -0.01D;
		
		return 0.03D;
	}
	
	public ChemicalStyle getStyle() {
		return getStyleFromType(this.getType());
	}
	
	public static ChemicalStyle getStyleFromType(FluidType type) {
		
		if(type.isAntimatter()) {
			return ChemicalStyle.AMAT;
		}
		
		if(type.hasTrait(Fluids.GASEOUS.getClass()) || type.hasTrait(Fluids.EVAP.getClass())) {
			
			if(type.hasTrait(FT_Flammable.class) || type.hasTrait(FT_Combustible.class)) {
				return ChemicalStyle.GASFLAME;
			} else {
				return ChemicalStyle.GAS;
			}
		}
		
		if(type.hasTrait(Fluids.LIQUID.getClass())) {
			
			if(type.hasTrait(FT_Combustible.class)) {
				return ChemicalStyle.BURNING;
			} else {
				return ChemicalStyle.LIQUID;
			}
		}
		
		return ChemicalStyle.NULL;
	}

	/**
	 * The general type of the chemical, determines rendering and movement
	 */
	public static enum ChemicalStyle {
		AMAT,		//renders as beam
		LIQUID,		//no renderer, fluid particles
		GAS,		//renders as particles
		GASFLAME,	//renders as fire particles
		BURNING,	//no renderer, fire particles
		NULL
	}
}