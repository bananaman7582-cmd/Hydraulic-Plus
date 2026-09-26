architectury {
    common("neoforge", "fabric")
}

dependencies {
    compileOnly(libs.mixin)
    compileOnly(libs.mixinextras)
    compileOnly(libs.geyser.api)
    compileOnly(libs.geyser.core) {
        exclude(group = "io.netty")
        exclude(group = "io.netty.incubator")
    }

    api(libs.pack.converter)

    // pack-converter's team.unnamed:creative stack is compiled against adventure 4.x, whose
    // Key/Keyed types implement net.kyori.examination.Examinable. Geyser 2.11+ forces adventure 5.x
    // (which dropped the examination dependency), so javac can no longer resolve Examinable when
    // reading those creative class signatures. Put it back on the compile classpath only; at runtime
    // it is still provided via pack-converter's JiJ'd dependencies.
    compileOnly("net.kyori:examination-api:1.3.0")

    implementation(libs.auto.service)
    annotationProcessor(libs.auto.service)

    // Only here to suppress "unknown enum constant EnvType.CLIENT" warnings.
    compileOnly(libs.fabric.loader)
}
