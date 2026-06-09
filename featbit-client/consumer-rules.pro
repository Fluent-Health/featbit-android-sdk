# kotlinx.serialization keeps generated serializers; the plugin already adds the
# necessary rules. Keep the public SDK model classes' serializers as a safeguard.
-keepclassmembers class co.featbit.client.model.** {
    *** Companion;
}
-keepclasseswithmembers class co.featbit.client.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
