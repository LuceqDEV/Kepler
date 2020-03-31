package org.alexdev.kepler.game.room.tasks;

import org.alexdev.kepler.game.GameScheduler;
import org.alexdev.kepler.game.entity.Entity;
import org.alexdev.kepler.game.item.Item;
import org.alexdev.kepler.game.item.base.ItemBehaviour;
import org.alexdev.kepler.game.item.roller.EntityRollingAnalysis;
import org.alexdev.kepler.game.item.roller.ItemRollingAnalysis;
import org.alexdev.kepler.game.item.roller.RollerEntry;
import org.alexdev.kepler.game.pathfinder.Position;
import org.alexdev.kepler.game.room.Room;
import org.alexdev.kepler.log.Log;
import org.alexdev.kepler.messages.outgoing.rooms.items.SLIDEOBJECTBUNDLE;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RollerTask implements Runnable {
    private final Room room;

    public RollerTask(Room room) {
        this.room = room;
    }

    @Override
    public void run() {
        try {
            Map<Item, Pair<Item, Position>> itemsRolling = new LinkedHashMap<>();
            Map<Entity, Pair<Item, Position>> entitiesRolling = new LinkedHashMap<>();

            List<RollerEntry> rollerEntries = new ArrayList<>();

            ItemRollingAnalysis itemRollingAnalysis = new ItemRollingAnalysis();
            EntityRollingAnalysis entityRollingAnalysis = new EntityRollingAnalysis();

            if (this.room.getItems().stream().anyMatch(item -> item.hasBehaviour(ItemBehaviour.ROLLER))) {
                this.room.getMapping().regenerateCollisionMap();
            }

            for (Item roller : this.room.getItems()) {
                if (roller.getTile() == null) {
                    continue;
                }

                var rollerTile = roller.getTile();

                if (!roller.hasBehaviour(ItemBehaviour.ROLLER)) {
                    continue;
                }

                RollerEntry rollerEntry = new RollerEntry(roller);

                // Process items on rollers
                for (Item item : rollerTile.getItems()) {
                    if (item.hasBehaviour(ItemBehaviour.ROLLER)) {
                        continue;
                    }

                    if (itemsRolling.containsKey(item)) {
                        continue;
                    }

                    Position nextPosition = itemRollingAnalysis.canRoll(item, roller, this.room);

                    if (nextPosition != null) {
                        itemsRolling.put(item, Pair.of(roller, nextPosition));
                        rollerEntry.getRollingItems().add(item.getRollingData());
                    }

                }

                // Process entities on rollers
                //for (Entity entity : roller.getTile().getEntities()) {
                var rollerEntities = rollerTile.getEntities();

                if (rollerEntities != null && rollerEntities.size() > 0) {
                    var entity = rollerEntities.stream().findFirst().orElse(null);

                    if (entitiesRolling.containsKey(entity)) {
                        continue;
                    }

                    Position nextPosition = entityRollingAnalysis.canRoll(entity, roller, this.room);

                    if (nextPosition != null) {
                        entitiesRolling.put(entity, Pair.of(roller, nextPosition));
                        rollerEntry.setRollingEntity(entity);
                    }
                }
                //}

                // Roller entry has items or entities to roll so make sure the packet gets senr
                if (rollerEntry.getRollingEntity() != null || rollerEntry.getRollingItems().size() > 0) {
                    rollerEntries.add(rollerEntry);
                }
            }

            // Perform roll animation for entity
            for (var kvp : entitiesRolling.entrySet()) {
                entityRollingAnalysis.doRoll(kvp.getKey(), kvp.getValue().getLeft(), this.room, kvp.getKey().getRoomUser().getPosition(), kvp.getValue().getRight());
            }

            // Perform roll animation for item
            for (var kvp : itemsRolling.entrySet()) {
                Item item = kvp.getKey();

                if (!item.isCurrentRollBlocked()) {
                    itemRollingAnalysis.doRoll(kvp.getKey(), kvp.getValue().getLeft(), this.room, kvp.getKey().getPosition(), kvp.getValue().getRight());
                }

                item.save();
            }

            // Send roller packets
            for (RollerEntry entry : rollerEntries) {
                var rollingItems = new ArrayList<>(entry.getRollingItems());
                rollingItems.removeIf(item -> item.getItem().isCurrentRollBlocked());

                var entityRollerData = entry.getRollingEntity() == null ? null :
                        (entry.getRollingEntity().getRoomUser() == null ? null : entry.getRollingEntity().getRoomUser().getRollingData());

                this.room.send(new SLIDEOBJECTBUNDLE(entry.getRoller(), rollingItems, entityRollerData));
            }

            if (itemsRolling.size() > 0 || entitiesRolling.size() > 0) {
                this.room.getMapping().regenerateCollisionMap();
                GameScheduler.getInstance().getService().schedule(new RollerCompleteTask(itemsRolling.keySet(), entitiesRolling.keySet(), this.room), 800, TimeUnit.MILLISECONDS);
            }
        } catch (Exception ex) {
            Log.getErrorLogger().error("RollerTask crashed: ", ex);
        }
    }
}
