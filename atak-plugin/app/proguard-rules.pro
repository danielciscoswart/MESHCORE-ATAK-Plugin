# ATAK loads the class named in assets/plugin.xml by reflection.
-keep class dev.meshcore.atak.host.MeshCoreAtakPlugin {
    public <init>(gov.tak.api.plugin.IServiceController);
}
