package org.kodein.rest.nomi


/** Overrides `fillInStackTrace` to record no frames at all, which is the whole point of it. */
@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
public actual abstract class LightException actual constructor(message: String?, cause: Throwable?) : Exception(message, cause) {
    override fun fillInStackTrace(): Throwable {
        stackTrace = emptyArray()
        return this
    }
}
