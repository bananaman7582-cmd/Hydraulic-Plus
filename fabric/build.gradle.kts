val modId = project.property("mod_id") as String

architectury {
    platformSetupLoomIde()
    fabric()
}

val common: Configuration by configurations.creating
val developmentFabric: Configuration = configurations.getByName("developmentFabric")
val includeTransitive: Configuration = configurations.getByName("includeTransitive")

configurations {
    compileClasspath.get().extendsFrom(configurations["common"])
    runtimeClasspath.get().extendsFrom(configurations["common"])
    developmentFabric.extendsFrom(configurations["common"])
}

tasks {
    named<Jar>("mergeShadowAndJarJar") {
        from (
            zipTree( shadowJar.map { it.outputs.files.singleFile } ).matching {
                exclude("fabric.mod.json")
                exclude("LICENSE")
            },
            zipTree( jar.map { it.outputs.files.singleFile } ).matching {
                include("META-INF/jars/**")
                include("fabric.mod.json")
                include("LICENSE")
            }
        )
        archiveBaseName.set("${modId}-fabric")
    }

    shadowJar {
        archiveClassifier.set("dev-shadow")

        // Geyser-Fabric only shades part of Cloudburst: its protocol classes are relocated, but NBT
        // arrives as an ordinary jar-in-jar dependency and keeps its original name - compare
        // GeyserSmithingRecipe (shaded protocol types) with CustomItemRegistryPopulator, whose
        // methods take a plain org.cloudburstmc.nbt.NbtMapBuilder. Relocating all of org.cloudburstmc
        // therefore rewrote our NBT references to a shaded name nothing here uses. That also rewrites
        // the target strings inside mixin annotations, so such a mixin binds to nothing, and a mixin
        // that fails to bind aborts the class transform and stops Geyser loading entirely.
        relocate("org.cloudburstmc.protocol", "org.geysermc.geyser.shaded.org.cloudburstmc.protocol")
    }

    jar {
        archiveClassifier.set("dev")
    }
}

dependencies {
    implementation(libs.fabric.loader)
    api(libs.fabric.api)
    common(project(":shared")) { isTransitive = false }
    compileOnly(libs.geyser.api)

    shadow(project(path = ":shared", configuration = "transformProductionFabric")) {
        isTransitive = false
    }

    compileOnly(libs.asm)

    // Compiled against so the Polymer mixins can name real types; never required at runtime. The
    // mixin config plugin beside them declines to apply them when Polymer is not installed
    compileOnly("eu.pb4:polymer-core:0.17.4+26.2")

    runtimeOnly(libs.pack.converter)
    includeTransitive(libs.pack.converter)

    localRuntime(libs.geyser.fabric) {
        exclude(group = "io.netty")
        exclude(group = "io.netty.incubator")
        exclude(group = "org.incendo")
    }
}

sourceSets {
    main {
        resources {
            srcDirs(project(":shared").sourceSets["main"].resources.srcDirs)
        }
    }
}