package org.kodein.rest.nomi

/** Nothing to suppress here: the JS and Wasm `Error` carries no stack trace to opt out of. */
@Suppress(names = ["EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING"])
public actual abstract class LightException actual constructor(message: String?, cause: Throwable?) : Exception(message, cause)
