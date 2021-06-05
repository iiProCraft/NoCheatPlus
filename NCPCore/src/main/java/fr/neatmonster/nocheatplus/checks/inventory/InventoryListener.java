/*
 * This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package fr.neatmonster.nocheatplus.checks.inventory;

import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.ComplexEntityPart;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.InventoryType.SlotType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import fr.neatmonster.nocheatplus.NCPAPIProvider;
import fr.neatmonster.nocheatplus.checks.CheckListener;
import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.checks.combined.Combined;
import fr.neatmonster.nocheatplus.checks.combined.Improbable;
import fr.neatmonster.nocheatplus.checks.moving.MovingData;
import fr.neatmonster.nocheatplus.checks.moving.util.MovingUtil;
import fr.neatmonster.nocheatplus.compat.Bridge1_9;
import fr.neatmonster.nocheatplus.compat.BridgeHealth;
import fr.neatmonster.nocheatplus.components.NoCheatPlusAPI;
import fr.neatmonster.nocheatplus.components.data.ICheckData;
import fr.neatmonster.nocheatplus.components.data.IData;
import fr.neatmonster.nocheatplus.components.entity.IEntityAccessVehicle;
import fr.neatmonster.nocheatplus.components.registry.event.IGenericInstanceHandle;
import fr.neatmonster.nocheatplus.components.registry.factory.IFactoryOne;
import fr.neatmonster.nocheatplus.components.registry.feature.JoinLeaveListener;
import fr.neatmonster.nocheatplus.players.DataManager;
import fr.neatmonster.nocheatplus.players.IPlayerData;
import fr.neatmonster.nocheatplus.players.PlayerFactoryArgument;
import fr.neatmonster.nocheatplus.stats.Counters;
import fr.neatmonster.nocheatplus.utilities.InventoryUtil;
import fr.neatmonster.nocheatplus.utilities.ReflectionUtil;
import fr.neatmonster.nocheatplus.utilities.map.MaterialUtil;
import fr.neatmonster.nocheatplus.worlds.WorldFactoryArgument;

/**
 * Central location to listen to events that are relevant for the inventory checks.
 * 
 * @see InventoryEvent
 */
public class InventoryListener  extends CheckListener implements JoinLeaveListener{

    /** The drop check. */
    private final Drop       drop       = addCheck(new Drop());
    
    /** Inventory Move check */
    private final InventoryMove invMove = addCheck(new InventoryMove());
    
    /** More Inventory check */
    private final MoreInventory moreInv = addCheck(new MoreInventory());

    /** The fast click check. */
    private final FastClick  fastClick  = addCheck(new FastClick());

    /** The instant bow check. */
    private final InstantBow instantBow = addCheck(new InstantBow());

    /** The instant eat check. */
    private final InstantEat instantEat = addCheck(new InstantEat());

    protected final Items items         = addCheck(new Items());

    private final Open open             = addCheck(new Open());
    
    private boolean keepCancel = false;

    private final boolean hasInventoryAction;

    /** For temporary use: LocUtil.clone before passing deeply, call setWorld(null) after use. */
    private final Location useLoc = new Location(null, 0, 0, 0);

    private final Counters counters = NCPAPIProvider.getNoCheatPlusAPI().getGenericInstance(Counters.class);
    private final int idCancelDead = counters.registerKey("cancel.dead");
    private final int idIllegalItem = counters.registerKey("illegalitem");
    private final int idEggOnEntity = counters.registerKey("eggonentity");

    private final IGenericInstanceHandle<IEntityAccessVehicle> handleVehicles = 
            NCPAPIProvider.getNoCheatPlusAPI().getGenericInstanceHandle(IEntityAccessVehicle.class);

    @SuppressWarnings("unchecked")
    public InventoryListener() {
        super(CheckType.INVENTORY);
        final NoCheatPlusAPI api = NCPAPIProvider.getNoCheatPlusAPI();
        api.register(api.newRegistrationContext()
                // InventoryConfig
                .registerConfigWorld(InventoryConfig.class)
                .factory(new IFactoryOne<WorldFactoryArgument, InventoryConfig>() {
                    @Override
                    public InventoryConfig getNewInstance(
                            WorldFactoryArgument arg) {
                        return new InventoryConfig(arg.worldData);
                    }
                })
                .registerConfigTypesPlayer()
                .context() //
                // InventoryData
                .registerDataPlayer(InventoryData.class)
                .factory(new IFactoryOne<PlayerFactoryArgument, InventoryData>() {
                    @Override
                    public InventoryData getNewInstance(
                            PlayerFactoryArgument arg) {
                        return new InventoryData();
                    }
                })
                .addToGroups(CheckType.INVENTORY, true, IData.class, ICheckData.class)
                .context() //
                );
        hasInventoryAction = ReflectionUtil.getClass("org.bukkit.event.inventory.InventoryAction") != null;
    }

    /**
     * We listen to EntityShootBow events for the InstantBow check.
     * 
     * @param event
     *            the event
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onEntityShootBow(final EntityShootBowEvent event) {
        
        // Only if a player shot the arrow.
        if (event.getEntity() instanceof Player) {
            final Player player = (Player) event.getEntity();
            final IPlayerData pData = DataManager.getPlayerData(player);
            if (instantBow.isEnabled(player, pData)) {
                final long now = System.currentTimeMillis();
                final Location loc = player.getLocation(useLoc);
                if (Combined.checkYawRate(player, loc.getYaw(), now, 
                        loc.getWorld().getName(), pData)) {
                    // No else if with this, could be cancelled due to other checks feeding, does not have actions.
                    event.setCancelled(true);
                }
                final InventoryConfig cc = pData.getGenericInstance(InventoryConfig.class);
                // Still check instantBow, whatever yawrate says.
                if (instantBow.check(player, event.getForce(), now)) {
                    // The check requested the event to be cancelled.
                    event.setCancelled(true);
                }
                else if (cc.instantBowImprobableWeight > 0.0f) {
                    if (cc.instantBowImprobableFeedOnly) {
                        Improbable.feed(player, cc.instantBowImprobableWeight, now);
                    }
                    else if (Improbable.check(player, cc.instantBowImprobableWeight, 
                            now, "inventory.instantbow", pData)) {
                        // Combined fighting speed (Else if: Matter of taste, preventing extreme cascading and actions spam).
                        event.setCancelled(true);
                    }
                }
                useLoc.setWorld(null);
            }  
        }
    }

    /**
     * We listen to FoodLevelChange events because Bukkit doesn't provide a PlayerFoodEating Event (or whatever it would
     * be called).
     * 
     * @param event
     *            the event
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onFoodLevelChange(final FoodLevelChangeEvent event) {

        // Only if a player ate food.
        if (event.getEntity() instanceof Player) {
            final Player player = (Player) event.getEntity();
            final IPlayerData pData = DataManager.getPlayerData(player);
            if (instantEat.isEnabled(player, pData) 
                    && instantEat.check(player, event.getFoodLevel())) {
                event.setCancelled(true);
            }
            else if (player.isDead() && BridgeHealth.getHealth(player) <= 0.0) {
                // Eat after death.
                event.setCancelled(true);
                counters.addPrimaryThread(idCancelDead, 1);
            }
        }
    }

    /**
     * We listen to InventoryClick events for the FastClick check.
     * 
     * @param event
     *            the event
     */
    @SuppressWarnings("deprecation")
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onInventoryClick(final InventoryClickEvent event) {

        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        final long now = System.currentTimeMillis();
        final HumanEntity entity = event.getWhoClicked();
        if (!(entity instanceof Player)) {
            return;
        }
        final Player player = (Player) entity;
        final IPlayerData pData = DataManager.getPlayerData(player);

        if (!pData.isCheckActive(CheckType.INVENTORY, player)) return;

        final InventoryData data = pData.getGenericInstance(InventoryData.class);
        final int slot = event.getSlot();
        final String inventoryAction = hasInventoryAction ? event.getAction().name() : null;
        if (pData.isDebugActive(checkType)) {
            outputDebugInventoryClick(player, slot, event, inventoryAction);
        }
        if (slot == InventoryView.OUTSIDE || slot < 0) {
            data.lastClickTime = now;
            return;
        }

        final ItemStack cursor = event.getCursor();
        final ItemStack clicked = event.getCurrentItem();
        boolean cancel = false;
        // Illegal enchantment checks.
        try{
            if (Items.checkIllegalEnchantments(player, clicked, pData)) {
                cancel = true;
                counters.addPrimaryThread(idIllegalItem, 1);
            }
        }
        catch(final ArrayIndexOutOfBoundsException e) {} // Hotfix (CB)
        try{
            if (!cancel && Items.checkIllegalEnchantments(player, cursor, pData)) {
                cancel = true;
                counters.addPrimaryThread(idIllegalItem, 1);
            }
        }
        catch(final ArrayIndexOutOfBoundsException e) {} // Hotfix (CB)

        // Fast inventory manipulation check.
        if (fastClick.isEnabled(player, pData)) {
            final InventoryConfig cc = pData.getGenericInstance(InventoryConfig.class);
            if (!((event.getView().getType().equals(InventoryType.CREATIVE) || player.getGameMode() == GameMode.CREATIVE) && cc.fastClickSpareCreative)) {
                boolean check = true;
                try {
                    check = !cc.inventoryExemptions.contains(ChatColor.stripColor(event.getView().getTitle()));
                }
                catch (final IllegalStateException e) {
                    check = true; //...
                }
                if (check && fastClick.check(player, now, 
                        event.getView(), slot, cursor, clicked, event.isShiftClick(), 
                        inventoryAction, data, cc, pData)) {  
                    // The check requested the event to be cancelled.
                    cancel = true;
                }
                // Listen for more than just a chest?
                if (check){
                    if (event.getInventory().getType().equals(InventoryType.CHEST) || event.getInventory().getType().equals(InventoryType.ENDER_CHEST) 
                        || event.getInventory().getType().toString().equals("BARREL") 
                        || event.getInventory().getType().toString().equals("SHULKER_BOX")) {
                        if (fastClick.fastClickChest(player, data, cc)) {
                            cancel = true;
                            keepCancel = true;
                        }
                    }
                }
            }
        }
        
        // Inventory Move check
        final SlotType type = event.getSlotType();
        if (invMove.isEnabled(player, pData)) {
            final InventoryConfig cc = pData.getGenericInstance(InventoryConfig.class);
                if (invMove.check(player, data, pData, cc, type)) {
                    cancel = true;  
            }
        }
        
        
        data.lastClickTime = now;
        if (cancel || keepCancel) {
            event.setCancelled(true);
        }
    }
    
    /** 
    * Listens for when a player closes a chest.
    * We do this to keep canceling the attempt to click within the chest if
    * fastClickChest is true. 
    */
    @EventHandler(priority = EventPriority.MONITOR)
    public void closeChest(InventoryCloseEvent event) {
        keepCancel = false;
    }
    
    /** 
    * Listens for when a player opens a chest.
    * We do this to compare the times between opening a chest and
    * interacting with it.
    */
    @EventHandler(priority = EventPriority.MONITOR)
    public void chestOpen(PlayerInteractEvent event) {

        final Player player = event.getPlayer();
        final IPlayerData pData = DataManager.getPlayerData(player);
        final InventoryData data = pData.getGenericInstance(InventoryData.class);

        if (!pData.isCheckActive(CheckType.INVENTORY, player)) return;
        
        // Check left click too to prevent any bypasses
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null 
            || event.getAction() == Action.LEFT_CLICK_BLOCK && event.getClickedBlock() != null){

            if (event.getClickedBlock().getType().toString().endsWith("CHEST") 
                || event.getClickedBlock().getType().toString().equals("BARREL") 
                || event.getClickedBlock().getType().toString().endsWith("SHULKER_BOX")) {
               data.chestOpenTime = System.currentTimeMillis();
            }
        } 
    }

    /**
     * Debug inventory classes. Contains information about classes, to indicate
     * if cross-plugin compatibility issues can be dealt with easily.
     * 
     * @param player
     * @param slot
     * @param event
     */
    private void outputDebugInventoryClick(final Player player, final int slot, final InventoryClickEvent event, 
                                           final String action) {

        // TODO: Check if this breaks legacy compat (disable there perhaps).
        // TODO: Consider only logging where different from expected (CraftXY, more/other viewer than player). 

        final StringBuilder builder = new StringBuilder(512);
        builder.append("Inventory click: slot: " + slot);

        // Viewers.
        builder.append(" , Viewers: ");
        for (final HumanEntity entity : event.getViewers()) {
            builder.append(entity.getName());
            builder.append("(");
            builder.append(entity.getClass().getName());
            builder.append(")");
        }

        // Inventory view.
        builder.append(" , View: ");
        final InventoryView view = event.getView();
        builder.append(view.getClass().getName());

        // Bottom inventory.
        addInventory(view.getBottomInventory(), view, " , Bottom: ", builder);

        // Top inventory.
        addInventory(view.getBottomInventory(), view, " , Top: ", builder);
        
        if (action != null) {
            builder.append(" , Action: ");
            builder.append(action);
        }

        // Event class.
        builder.append(" , Event: ");
        builder.append(event.getClass().getName());

        // Log debug.
        debug(player, builder.toString());
    }

    private void addInventory(final Inventory inventory, final InventoryView view, final String prefix,
            final StringBuilder builder) {
        builder.append(prefix);
        if (inventory == null) {
            builder.append("(none)");
        }
        else {
            String name = view.getTitle();
            builder.append(name);
            builder.append("/");
            builder.append(inventory.getClass().getName());
        }
    }

    /**
     * We listen to DropItem events for the Drop check.
     * 
     * @param event
     *            the event
     */
    @EventHandler( ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onPlayerDropItem(final PlayerDropItemEvent event) {

        final Player player = event.getPlayer();
        final IPlayerData pData = DataManager.getPlayerData(player);

        if (!pData.isCheckActive(CheckType.INVENTORY, player)) return;

        // Illegal enchantments hotfix check.
        final Item item = event.getItemDrop();
        if (item != null) {
            // No cancel here.
            Items.checkIllegalEnchantments(player, item.getItemStack(), pData);
        }

        // If the player died, all their items are dropped so ignore them.
        if (event.getPlayer().isDead())
            return;

        if (pData.isCheckActive(CheckType.INVENTORY_DROP, player)) {
            if (drop.check(player)) {
                // TODO: Is the following command still correct? If so, adapt actions.
                /*
                 * Cancelling drop events is not save (in certain circumstances
                 * items will disappear completely). So don't
                 */
                // do it and kick players instead by default.
                event.setCancelled(true);
            }
        }


    }

    /**
     * We listen to PlayerInteract events for the InstantEat and InstantBow checks.
     * 
     * @param event
     *            the event
     */
    @EventHandler(ignoreCancelled = false, priority = EventPriority.LOWEST)
    public final void onPlayerInteract(final PlayerInteractEvent event) {

        // Only interested in right-clicks while holding an item.
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)
            return;

        final Player player = event.getPlayer();
        final IPlayerData pData = DataManager.getPlayerData(player);
        final InventoryData data = pData.getGenericInstance(InventoryData.class);

        if (!pData.isCheckActive(CheckType.INVENTORY, player)) return;

        boolean resetAll = false;

        if (event.hasItem()) {
            final ItemStack item = event.getItem();
            final Material type = item.getType();
            // TODO: Get Magic values (800) from the config.
            // TODO: Cancelled / deny use item -> reset all?
            if (type == Material.BOW) {
                final long now = System.currentTimeMillis();
                // It was a bow, the player starts to pull the string, remember this time.
                data.instantBowInteract = (data.instantBowInteract > 0 && now - data.instantBowInteract < 800) 
                        ? Math.min(System.currentTimeMillis(), data.instantBowInteract) : System.currentTimeMillis();
            }
            else if (InventoryUtil.isConsumable(type)) {
                final long now = System.currentTimeMillis();
                // It was food, the player starts to eat some food, remember this time and the type of food.
                data.instantEatFood = type;
                data.instantEatInteract = (data.instantEatInteract > 0 && now - data.instantEatInteract < 800) 
                        ? Math.min(System.currentTimeMillis(), data.instantEatInteract) : System.currentTimeMillis();
                        data.instantBowInteract = 0; // Who's monitoring this indentation code?
            } else resetAll = true;

            // Illegal enchantments hotfix check.
            if (Items.checkIllegalEnchantments(player, item, pData)) {
                event.setCancelled(true);
                counters.addPrimaryThread(idIllegalItem, 1);
            }
        }
        else {
            resetAll = true;
        }

        if (resetAll) {
            // Nothing that we are interested in, reset data.
            if (pData.isDebugActive(CheckType.INVENTORY_INSTANTEAT) 
                    && data.instantEatFood != null) {
                debug(player, "PlayerInteractEvent, reset fastconsume (legacy: instanteat).");
            }
            data.instantBowInteract = 0;
            data.instantEatInteract = 0;
            data.instantEatFood = null;
        }
    }

    @EventHandler(ignoreCancelled = false, priority = EventPriority.LOWEST)
    public final void onPlayerInteractEntity(final PlayerInteractEntityEvent event) {

        final Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE || !DataManager.getPlayerData(player).isCheckActive(CheckType.INVENTORY, player)) {
            return;
        }
        if (player.isDead() && BridgeHealth.getHealth(player) <= 0.0) {
            // No zombies.
            event.setCancelled(true);
            counters.addPrimaryThread(idCancelDead, 1);
            return;
        }
        else if (MovingUtil.hasScheduledPlayerSetBack(player)) {
            event.setCancelled(true);
            return;
        }
        // TODO: Activate mob-egg check only for specific server versions.
        final ItemStack stack = Bridge1_9.getUsedItem(player, event);
        Entity entity = event.getRightClicked();
        if (stack != null &&  MaterialUtil.isSpawnEgg(stack.getType())
                && (entity == null || entity instanceof LivingEntity  || entity instanceof ComplexEntityPart)
                && items.isEnabled(player, DataManager.getPlayerData(player))) {
            event.setCancelled(true);
            counters.addPrimaryThread(idEggOnEntity, 1);
            return;
        }
    }

    @EventHandler(ignoreCancelled = false, priority = EventPriority.LOWEST)
    public final void onPlayerInventoryOpen(final InventoryOpenEvent event) {

        // Possibly already prevented by block + entity interaction.
        final HumanEntity entity = event.getPlayer();
        if (entity instanceof Player) {
            if (MovingUtil.hasScheduledPlayerSetBack((Player) entity)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemHeldChange(final PlayerItemHeldEvent event) {

        final Player player = event.getPlayer();
        final IPlayerData pData = DataManager.getPlayerData(player);
        final InventoryData data = pData.getGenericInstance(InventoryData.class);

        if (!pData.isCheckActive(CheckType.INVENTORY, player)) return;

        if (pData.isDebugActive(checkType) && data.instantEatFood != null) {
            debug(player, "PlayerItemHeldEvent, reset fastconsume (legacy: instanteat).");
        }
        data.instantBowInteract = 0;
        data.instantEatInteract = System.currentTimeMillis();
        data.instantEatFood = null;

        // Illegal enchantments hotfix check.
        final PlayerInventory inv = player.getInventory();
        Items.checkIllegalEnchantments(player, inv.getItem(event.getNewSlot()), pData);
        Items.checkIllegalEnchantments(player, inv.getItem(event.getPreviousSlot()), pData);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(final PlayerChangedWorldEvent event) {

        open.check(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerPortal(final PlayerPortalEvent event) {

        // Note: ignore cancelother setting.
        open.check(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityPortal(final EntityPortalEnterEvent event) {

        // Check passengers flat for now.
        final Entity entity = event.getEntity();
        if (entity instanceof Player) {
            open.check((Player) entity);
        }
        else {
            for (final Entity passenger : handleVehicles.getHandle().getEntityPassengers(entity)) {
                if (passenger instanceof Player) {
                    // Note: ignore cancelother setting.
                    open.check((Player) passenger);
                }
            }
        }
    }
    
    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(final PlayerMoveEvent event) {

        final Player player = event.getPlayer();
        final Location from = event.getFrom();
        final Location to = event.getTo();
        final boolean PoYdiff = from.getPitch() != to.getPitch() || from.getYaw() != to.getYaw();
        final IPlayerData pData = DataManager.getPlayerData(player);
        if (pData == null) return;

        if (!pData.isCheckActive(CheckType.INVENTORY, player)) return;

        final InventoryData iData = pData.getGenericInstance(InventoryData.class);
        final MovingData data = pData.getGenericInstance(MovingData.class);
        final Inventory inv = player.getOpenInventory().getTopInventory();
        if (moreInv.isEnabled(player, pData) 
            && moreInv.check(player, data, pData, inv.getType(), inv, PoYdiff)) {
            // Just simply close inventory ?
            for (int i =1; i<=4 ;i++) {
                final ItemStack item = inv.getItem(i);
                if (item != null && item.getType() != Material.AIR) {
                    player.getWorld().dropItemNaturally(player.getLocation(), item);
                    inv.setItem(i, null);
                    if (pData.isDebugActive(CheckType.INVENTORY_MOREINVENTORY))
                        debug(player , "Drop items from crafting slot:" + i);
                }
            }
        }

        // TODO: Let's check for certain conditions here, to see if the player is
        // Actually moving and not just moving from other events (Ice, falling, velocity)
        // TODO: Other concept of InventoryMove , merge MoreInventory, confine more(close inv on jump) ?
        iData.lastMoveEvent = System.currentTimeMillis();

    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerTeleport(final PlayerTeleportEvent event) {
        // Note: ignore cancelother setting.
        open.check(event.getPlayer());
    }

    @Override
    public void playerJoins(Player player) {
        // Ignore
    }

    @Override
    public void playerLeaves(Player player) {
        open.check(player);
    }

    //    @EventHandler(priority = EventPriority.MONITOR)
    //    public void onVehicleDestroy(final VehicleDestroyEvent event) {
    //      final Entity entity = event.getVehicle();
    //      if (entity instanceof InventoryHolder) { // Fail on 1.4 ?
    //          checkInventoryHolder((InventoryHolder) entity);
    //      }
    //    }
    //    
    //    @EventHandler(priority = EventPriority.MONITOR)
    //    public void onBlockBreak(final BlockBreakEvent event) {
    //      final Block block = event.getBlock();
    //      if (block == null) {
    //          return;
    //      }
    //      // TODO: + explosions !? + entity change block + ...
    //    }
    //
    //  private void checkInventoryHolder(InventoryHolder entity) {
    //      // TODO Auto-generated method stub
    //      
    //  }

}
