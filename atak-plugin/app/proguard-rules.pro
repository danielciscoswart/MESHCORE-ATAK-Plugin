# ATAK loads the class named in assets/plugin.xml by reflection.
# Do not add -repackageclasses. The SDK's proguard-release-keep.txt is applied
# by takdev from the local SDK during preReleaseBuild. It is not copied here.

-keep class dev.meshcore.atak.host.MeshCoreAtakPlugin {
    public <init>(gov.tak.api.plugin.IServiceController);
}

-keepclassmembers class dev.meshcore.atak.host.ExperimentalGeoChatWatch {
    public void logSend(com.atakmap.coremap.cot.event.CotEvent, java.lang.String);
    public void logSend(com.atakmap.coremap.cot.event.CotEvent, java.lang.String[]);
    public void logReceive(com.atakmap.coremap.cot.event.CotEvent, java.lang.String, java.lang.String);
    public void dispose();
}
