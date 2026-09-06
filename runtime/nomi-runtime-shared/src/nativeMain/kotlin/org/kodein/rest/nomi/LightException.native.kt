package org.kodein.rest.nomi

@Suppress(names = ["EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING"])
public actual abstract class LightException actual constructor(message: String?, cause: Throwable?) : Exception(message, cause)