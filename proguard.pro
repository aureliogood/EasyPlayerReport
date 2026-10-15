# EasyPlayerReport - reglas de ofuscacion (sintaxis de ProGuard 7).
#
# Bukkit carga la main class por el campo 'main' de plugin.yml, asi que su
# nombre no se puede cambiar. Todo lo demas (clases, metodos, campos) se
# renombra, y los ficheros de recursos viajan sin tocar para que comandos,
# permisos y claves de config sigan funcionando.

# Sin -target: ProGuard solo puede bajar la version del class file, nunca
# subirla, y maven.compiler ya fija 21. Ponerlo aqui solo carga avisos de
# "deprecated when using class file above 55".

# Main class: nombre intacto y constructor publico sin parametros.
-keep class com.easyplayerreport.EasyPlayerReport {
    public <init>();
}

# El servidor los invoca por convencion, no por referencia.
-keepclassmembers class com.easyplayerreport.EasyPlayerReport {
    public void onEnable();
    public void onDisable();
}

# Bukkit registra los listeners recorriendo las clases y buscando metodos
# anotados con @EventHandler, no por implements Listener. Sin esto el
# renombrado los rompe el registro en silencio.
-keepclassmembers class com.easyplayerreport.** {
    @org.bukkit.event.EventHandler <methods>;
}

# CommandExecutor / TabCompleter: el servidor guarda la instancia en el
# PluginCommand y los vuelve a invocar en cada ejecucion.
-keepclassmembers class com.easyplayerreport.** implements org.bukkit.command.TabExecutor {
    <methods>;
}

# No se pide SourceFile ni LineNumberTable a proposito: los stack traces no
# revelan ni el nombre del fichero ni la linea, que es de donde se saca la
# estructura del proyecto.
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions,ConstantValue,*Annotation*

# Dependencias provided: se resuelven en runtime, ProGuard solo avisa.
-dontwarn org.bukkit.**
-dontwarn org.spigotmc.**
-dontwarn io.papermc.**
-dontwarn net.kyori.**
-dontwarn com.mysql.**
-dontwarn org.sqlite.**
-dontwarn org.checkerframework.**
-dontwarn org.jetbrains.**
-dontwarn org.slf4j.**
-dontwarn com.zaxxer.**
-dontwarn javax.naming.**
-dontwarn java.lang.invoke.**
-dontwarn org.apache.logging.**