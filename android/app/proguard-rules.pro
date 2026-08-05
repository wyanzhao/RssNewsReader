-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault,InnerClasses,EnclosingMethod

# kotlinx.serialization serializers are normally referenced statically; retain
# companion serializer entry points for artifacts decoded through generic APIs.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$Companion Companion;
}
-keepclassmembers class <1>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers,includedescriptorclasses class **$* {
    kotlinx.serialization.KSerializer serializer(...);
}
