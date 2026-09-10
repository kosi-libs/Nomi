package org.kodein.rest.nomi


/**
 * Base class for the exceptions Nomi throws on its own control-flow paths, where the stack trace is
 * never read: capturing one costs far more than the throw itself.
 *
 * Each platform's `actual` suppresses that capture as far as it can - see the JVM one, which is
 * where it matters most.
 */
@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
public expect abstract class LightException(message: String? = null, cause: Throwable? = null) : Exception
