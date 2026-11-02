# UVC ライブラリは JNI を使うため、難読化する場合はネイティブメソッドを保持すること。
# v0.1 は minify 無効なので実質未使用。
-keep class com.serenegiant.** { *; }
-keep class com.herohan.** { *; }
