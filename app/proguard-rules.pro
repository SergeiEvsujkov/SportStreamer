# Правила R8 для release-сборки.

# SDK обновлений RuStore активно использует отражение и Kotlin-метаданные:
# без этих правил release-вариант может падать при проверке обновлений.
-keep class ru.rustore.** { *; }
-keep class ru.vk.store.** { *; }
-dontwarn ru.rustore.**
-dontwarn ru.vk.store.**

# Атрибуты нужны библиотекам, которые читают аннотации и сигнатуры во время работы.
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod
