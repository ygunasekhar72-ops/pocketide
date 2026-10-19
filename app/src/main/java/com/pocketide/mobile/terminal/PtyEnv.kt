package com.pocketide.mobile.terminal

/**
 * Pure Kotlin construction of the child environment for the P3a native PTY.
 *
 * Kept free of JNI/loading side effects so it stays unit-testable on the
 * plain JVM test runner (the native part needs an Android runtime).
 *
 * Base environment (documented POC semantics):
 *  - PATH=/system/bin:/system/xbin   Android system shell + toybox coreutils
 *  - TERM=xterm-256color             standard for upcoming terminal renderers
 *  - HOME=<spec.cwd>                 POC value; a real profile home is a later
 *                                    milestone decision
 * Entries of [PtySpec.env] override base entries by exact key (last wins),
 * all other keys are appended. Keys are validated: `NAME` must match
 * `[A-Za-z_][A-Za-z0-9_]*` - execve rejects neither dashes nor spaces, but
 * shells and Android tooling expect portable identifiers; invalid keys throw.
 */
object PtyEnv {

    private val KEY_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_]*")

    const val BASE_PATH = "/system/bin:/system/xbin"
    const val BASE_TERM = "xterm-256color"

    fun build(spec: PtySpec): Array<String> {
        val base = linkedMapOf(
            "PATH" to BASE_PATH,
            "TERM" to BASE_TERM,
            "HOME" to spec.cwd.absolutePath
        )
        for ((key, value) in spec.env) {
            require(KEY_PATTERN.matches(key)) {
                "invalid environment variable name: \"$key\""
            }
            base[key] = value
        }
        return base.map { (k, v) -> "$k=$v" }.toTypedArray()
    }
}
