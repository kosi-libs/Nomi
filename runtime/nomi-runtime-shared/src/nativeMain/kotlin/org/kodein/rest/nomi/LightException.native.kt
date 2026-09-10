package org.kodein.rest.nomi

/** Nothing to suppress here: Kotlin/Native has no eager stack trace capture to opt out of. */
@Suppress(names = ["EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING"])
public actual abstract class LightException actual constructor(message: String?, cause: Throwable?) : Exception(message, cause)