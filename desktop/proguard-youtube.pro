# roma's own, beside proguard-rules.pro.
-printmapping build/proguard-mapping.txt
# CEF's process (CefHost.kt) runs with jars that aren't packaged (Browser.kt downloads them): its main class and
# what it calls keep their names, and its references to those jars aren't checked here.
-keep class com.zakius.caravanserai.CefHostKt { *; }
-keep class com.zakius.caravanserai.Dirs { *; }
-dontwarn org.cef.**
-dontwarn me.friwi.**
