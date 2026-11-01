package roma.core

import com.ibm.icu.text.Transliterator

/** Android supplies android.icu; on the JVM the tests use ICU4J. */
val jvmIcu = Icu { id, text -> Transliterator.getInstance(id).transliterate(text) }
