package world.lankci.pass.season;

import org.bukkit.Material;

import java.util.List;

/** Один рівень сезону: назва, умова, іконка, нагорода (опис + команди). */
public record LevelDef(int number, String name, RequirementType requirementType, long requirementAmount,
                       Material icon, List<String> description, List<String> commands, String message) {
}
