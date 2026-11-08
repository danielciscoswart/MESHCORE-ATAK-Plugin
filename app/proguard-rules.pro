# ATAK loads the class named in assets/plugin.xml by reflection.
# The 5.6 build page says a repackageclasses line, when present, must name the
# plugin. The kept entry class stays on its original name so discovery still
# matches plugin.xml. The SDK keep file is applied by takdev and is not copied.

-repackageclasses atakplugin.MeshCoreAtak

-keep class dev.meshcore.atak.host.MeshCoreAtakPlugin {
    public <init>(gov.tak.api.plugin.IServiceController);
}

-keepclassmembers class dev.meshcore.atak.host.ExperimentalGeoChatWatch {
    public void logSend(com.atakmap.coremap.cot.event.CotEvent, java.lang.String);
    public void logSend(com.atakmap.coremap.cot.event.CotEvent, java.lang.String[]);
    public void logReceive(com.atakmap.coremap.cot.event.CotEvent, java.lang.String, java.lang.String);
    public void dispose();
}
