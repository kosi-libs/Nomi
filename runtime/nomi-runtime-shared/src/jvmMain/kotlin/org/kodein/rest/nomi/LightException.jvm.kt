package org.kodein.rest.nomi


@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
public actual abstract class LightException actual constructor(message: String?, cause: Throwable?) : Exception(message, cause) {
    override fun fillInStackTrace(): Throwable {
        stackTrace = emptyArray()
        return this
    }
}
