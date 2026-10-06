## Rules for NewPipeExtractor (from its README)
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**

## Rhino's optional JSR-223 engine and invokedynamic linker use JDK-only APIs that Android lacks;
## NewPipeExtractor never calls them.
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
-dontwarn java.beans.**
