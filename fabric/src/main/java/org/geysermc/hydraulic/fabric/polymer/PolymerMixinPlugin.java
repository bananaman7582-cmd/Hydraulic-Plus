package org.geysermc.hydraulic.fabric.polymer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Decides whether the Polymer mixins are applied at all.
 * <p>
 * They must not be applied when Polymer is absent, which is nearly always: Hydraulic does not depend
 * on it, and a mixin that cannot find what it is patching is not a warning here but a refusal to
 * start. That is worth being careful about - a mod that crashes every server which does not happen
 * to run Polymer is worse than one that has no Polymer support at all.
 */
public class PolymerMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String POLYMER = "polymer-core";

    /**
     * Read straight from the file rather than through Hydraulic's own config class. Mixins are
     * applied long before anything of Hydraulic's has been set up, so there is nothing to ask yet.
     */
    private static final String OPTION = "hidePolymerFromBedrock";

    private boolean apply;

    @Override
    public void onLoad(String mixinPackage) {
        boolean installed = FabricLoader.getInstance().isModLoaded(POLYMER);
        this.apply = installed && enabled();

        // Said out loud because a mixin that is never applied looks exactly like one that applied and
        // did nothing, and the two are fixed in different places
        if (this.apply) {
            LOGGER.info("Polymer is installed; Bedrock players will be sent the real blocks and items "
                    + "rather than Polymer's stand-ins for them");
        } else if (installed) {
            LOGGER.info("Polymer is installed, but hidePolymerFromBedrock is off, so Bedrock players "
                    + "will be sent the same stand-ins Java players get");
        }
    }

    private static boolean enabled() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("hydraulic").resolve("config.json");
        if (!Files.isRegularFile(file)) {
            return true; // the default, and what the file will say once it is written
        }

        try (Reader reader = Files.newBufferedReader(file)) {
            JsonObject config = JsonParser.parseReader(reader).getAsJsonObject();
            return !config.has(OPTION) || config.get(OPTION).getAsBoolean();
        } catch (Exception e) {
            return true; // an unreadable config is not a reason to behave differently
        }
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return this.apply;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
