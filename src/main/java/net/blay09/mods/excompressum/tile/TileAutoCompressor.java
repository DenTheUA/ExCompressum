package net.blay09.mods.excompressum.tile;

import cofh.api.energy.IEnergyReceiver;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;
import net.blay09.mods.excompressum.config.ProcessingConfig;
import net.blay09.mods.excompressum.registry.compressor.CompressedRecipe;
import net.blay09.mods.excompressum.registry.compressor.CompressedRecipeRegistry;
import net.blay09.mods.excompressum.utils.DefaultItemHandler;
import net.blay09.mods.excompressum.utils.EnergyStorageModifiable;
import net.blay09.mods.excompressum.utils.ItemHandlerAutomation;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.wrapper.RangedWrapper;
import net.minecraftforge.oredict.OreDictionary;

import javax.annotation.Nullable;

public class TileAutoCompressor extends TileEntityBase implements ITickable, IEnergyReceiver {

    private final EnergyStorageModifiable energyStorage = new EnergyStorageModifiable(32000);
    private final Multiset<CompressedRecipe> inputItems = HashMultiset.create();
    private final DefaultItemHandler itemHandler = new DefaultItemHandler(this, 24) {
        @Override
        public boolean isItemValid(int slot, ItemStack itemStack) {
            return slot >= 12 || CompressedRecipeRegistry.getRecipe(itemStack) != null;
        }
    };
    private final RangedWrapper inputSlots = new RangedWrapper(itemHandler, 0, 12);
    private final RangedWrapper outputSlots = new RangedWrapper(itemHandler, 12, 24);
    private final ItemHandlerAutomation itemHandlerAutomation = new ItemHandlerAutomation(itemHandler) {
        @Override
        public boolean canInsertItem(int slot, ItemStack itemStack) {
            return slot < 12 && CompressedRecipeRegistry.getRecipe(itemStack) != null;
        }

        @Override
        public boolean canExtractItem(int slot, int amount) {
            return slot >= 12;
        }
    };

    private ItemStack currentStack;
    private float progress;

    @Override
    public void update() {
        int effectiveEnergy = getEffectiveEnergy();
        if (energyStorage.getEnergyStored() > effectiveEnergy) {
            if (currentStack == null) {
                inputItems.clear();
                for (int i = 0; i < inputSlots.getSlots(); i++) {
                    ItemStack slotStack = inputSlots.getStackInSlot(i);
                    if (slotStack != null) {
                        CompressedRecipe compressedRecipe = CompressedRecipeRegistry.getRecipe(slotStack);
                        if (compressedRecipe != null) {
                            inputItems.add(compressedRecipe, slotStack.stackSize);
                        }
                    }
                }
                for (CompressedRecipe compressedRecipe : inputItems.elementSet()) {
                    ItemStack sourceStack = compressedRecipe.getSourceStack();
                    if (inputItems.count(compressedRecipe) >= sourceStack.stackSize) {
                        int space = 0;
                        for(int i = 0; i < outputSlots.getSlots(); i++) {
                            ItemStack slotStack = outputSlots.getStackInSlot(i);
                            if(slotStack == null) {
                                space = 64;
                            } else if(isItemEqualWildcard(slotStack, compressedRecipe.getResultStack())) {
                                space += slotStack.getMaxStackSize() - slotStack.stackSize;
                            }
                            if(space >= compressedRecipe.getResultStack().stackSize) {
                                break;
                            }
                        }
                        if(space < compressedRecipe.getResultStack().stackSize) {
                            continue;
                        }
                        int count = sourceStack.stackSize;
                        for (int i = 0; i < inputSlots.getSlots(); i++) {
                            ItemStack slotStack = inputSlots.getStackInSlot(i);
                            if (slotStack != null && isItemEqualWildcard(slotStack, sourceStack)) {
                                if (slotStack.stackSize >= count) {
                                    slotStack.stackSize -= count;
                                    if (slotStack.stackSize == 0) {
                                        inputSlots.setStackInSlot(i, null);
                                    }
                                    count = 0;
                                    break;
                                } else {
                                    count -= slotStack.stackSize;
                                    inputSlots.setStackInSlot(i, null);
                                }
                            }
                        }
                        if (count <= 0) {
                            currentStack = sourceStack.copy();
                            progress = 0f;
                        }
                        break;
                    }
                }
            } else {
                energyStorage.extractEnergy(effectiveEnergy, false);
                progress = Math.min(1f, progress + getEffectiveSpeed());
                if (progress >= 1) {
                    if (!worldObj.isRemote) {
                        CompressedRecipe compressedRecipe = CompressedRecipeRegistry.getRecipe(currentStack);
                        if (compressedRecipe != null) {
                            ItemStack resultStack = compressedRecipe.getResultStack().copy();
                            if (!addItemToOutput(resultStack)) {
                                EntityItem entityItem = new EntityItem(worldObj, pos.getX() + 0.5, pos.getY() + 1.5, pos.getZ() + 0.5, resultStack);
                                double motion = 0.05;
                                entityItem.motionX = worldObj.rand.nextGaussian() * motion;
                                entityItem.motionY = 0.2;
                                entityItem.motionZ = worldObj.rand.nextGaussian() * motion;
                                worldObj.spawnEntityInWorld(entityItem);
                            }
                        }
                    }
                    currentStack = null;
                    progress = 0f;
                }
            }
        }
    }

    private boolean isItemEqualWildcard(ItemStack itemStack, ItemStack otherStack) {
        return ItemStack.areItemStackTagsEqual(itemStack, otherStack) && (itemStack.isItemEqual(otherStack)
                || itemStack.getItem() == otherStack.getItem() && (itemStack.getItemDamage() == OreDictionary.WILDCARD_VALUE
                || otherStack.getItemDamage() == OreDictionary.WILDCARD_VALUE));
    }

    private boolean addItemToOutput(ItemStack itemStack) {
        int firstEmptySlot = -1;
        for (int i = 0; i < outputSlots.getSlots(); i++) {
            ItemStack slotStack = outputSlots.getStackInSlot(i);
            if (slotStack == null) {
                if (firstEmptySlot == -1) {
                    firstEmptySlot = i;
                }
            } else {
                if (slotStack.stackSize + itemStack.stackSize <= slotStack.getMaxStackSize() && isItemEqualWildcard(slotStack, itemStack)) {
                    slotStack.stackSize += itemStack.stackSize;
                    return true;
                }
            }
        }
        if (firstEmptySlot != -1) {
            outputSlots.setStackInSlot(firstEmptySlot, itemStack);
            return true;
        }
        return false;
    }

    public int getEffectiveEnergy() {
        return ProcessingConfig.autoCompressorEnergy;
    }

    public float getEffectiveSpeed() {
        return ProcessingConfig.autoCompressorSpeed;
    }

    @Override
    protected boolean hasUpdatePacket() {
        return false;
    }

    @Override
    protected void readFromNBTSynced(NBTTagCompound tagCompound, boolean isSync) {
        currentStack = ItemStack.loadItemStackFromNBT(tagCompound.getCompoundTag("CurrentStack"));
        progress = tagCompound.getFloat("Progress");
        itemHandler.deserializeNBT(tagCompound.getCompoundTag("ItemHandler"));
        if(tagCompound.hasKey("EnergyStorage")) {
            CapabilityEnergy.ENERGY.readNBT(energyStorage, null, tagCompound.getTag("EnergyStorage"));
        }
    }

    @Override
    protected void writeToNBTSynced(NBTTagCompound tagCompound, boolean isSync) {
        if (currentStack != null) {
            tagCompound.setTag("CurrentStack", currentStack.writeToNBT(new NBTTagCompound()));
        }
        tagCompound.setFloat("Progress", progress);
        tagCompound.setTag("ItemHandler", itemHandler.serializeNBT());
        tagCompound.setTag("EnergyStorage", CapabilityEnergy.ENERGY.writeNBT(energyStorage, null));
    }

    @Override
    public int receiveEnergy(EnumFacing side, int maxReceive, boolean simulate) {
        return energyStorage.receiveEnergy(maxReceive, simulate);
    }

    @Override
    public int getEnergyStored(@Nullable EnumFacing side) {
        return energyStorage.getEnergyStored();
    }

    @Override
    public int getMaxEnergyStored(EnumFacing side) {
        return energyStorage.getMaxEnergyStored();
    }

    @Override
    public boolean canConnectEnergy(EnumFacing side) {
        return true;
    }

    public boolean isProcessing() {
        return progress > 0f;
    }

    public float getProgress() {
        return progress;
    }

    public void setProgress(float progress) {
        this.progress = progress;
    }

    public float getEnergyPercentage() {
        return (float) energyStorage.getEnergyStored() / (float) energyStorage.getMaxEnergyStored();
    }

    @Nullable
    public ItemStack getCurrentStack() {
        return currentStack;
    }

    @Override
    public boolean hasCapability(Capability<?> capability, EnumFacing facing) {
        return capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY
                || capability == CapabilityEnergy.ENERGY
                || super.hasCapability(capability, facing);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getCapability(Capability<T> capability, EnumFacing facing) {
        if(capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) {
            return (T) itemHandlerAutomation;
        }
        if(capability == CapabilityEnergy.ENERGY) {
            return (T) energyStorage;
        }
        return super.getCapability(capability, facing);
    }

    public DefaultItemHandler getItemHandler() {
        return itemHandler;
    }

    public EnergyStorageModifiable getEnergyStorage() {
        return energyStorage;
    }
}
