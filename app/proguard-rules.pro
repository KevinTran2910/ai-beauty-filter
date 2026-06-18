# Keep the JNI entry-point class and its native methods so symbol names match
# the C++ side (Java_com_aibeauty_beautyfilter_BeautyFilterNative_*).
-keep class com.aibeauty.beautyfilter.BeautyFilterNative { *; }
