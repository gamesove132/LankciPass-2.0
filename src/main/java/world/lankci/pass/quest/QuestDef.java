package world.lankci.pass.quest;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.util.List;
import java.util.Set;

/** Опис квесту з quests.yml. */
public record QuestDef(String id, String mode, QuestKind kind, String name, List<String> description,
                       String difficulty, QuestType type, long amount, long reward, Set<EntityType> entities,
                       Set<Material> materials, List<String> bonusCommands) {

    public boolean matchesEntity(EntityType t) {
        return entities.isEmpty() || entities.contains(t);
    }

    public boolean matchesMaterial(Material m) {
        return materials.isEmpty() || materials.contains(m);
    }
}
