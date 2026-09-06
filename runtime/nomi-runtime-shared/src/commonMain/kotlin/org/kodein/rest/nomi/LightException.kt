package org.kodein.rest.nomi


@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
public expect abstract class LightException(message: String? = null, cause: Throwable? = null) : Exception
