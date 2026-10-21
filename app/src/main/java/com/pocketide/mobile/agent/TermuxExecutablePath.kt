package com.pocketide.mobile.agent

/**
 * Trusted executable-path FORM policy for the Termux RUN_COMMAND backend,
 * completing the deferred part of the [AgentCommandSpec.executable] contract
 * ("validated by trusted code later, when a concrete backend is chosen").
 *
 * Provenance (Termux wiki, RUN_COMMAND-Intent page, rev. May 2026 — the same
 * pinned source as [TermuxPluginConstants]):
 *  - "The RUN_COMMAND_SERVICE.EXTRA_COMMAND_PATH and EXTRA_WORKDIR can
 *    optionally be prefixed with `$PREFIX/` or `~/` if an absolute path is
 *    not to be given. The `$PREFIX/` will expand to
 *    TermuxConstants.TERMUX_PREFIX_DIR_PATH and `~/` will expand to
 *    TermuxConstants.TERMUX_HOME_DIR_PATH."
 *
 * Exactly these two expansion prefixes — and nothing else — are therefore
 * accepted in non-absolute form. In particular `$HOME/...` is NOT a
 * RUN_COMMAND-path token documented at integration time and is rejected
 * fail-closed (an undocumented token would rely on unverified Termux
 * expansion behavior on the device; P3e re-verifies it against real builds).
 * Relative paths are rejected outright: Termux would resolve them inside its
 * own app context in under-documented ways, which this boundary cannot
 * honestly evaluate.
 *
 * Structural rules, deterministic and component-wise:
 *  - after the leading form (absolute, `$PREFIX/`, or `~/`), every '/'
 *    -separated component must be non-empty (rejects repeated or trailing
 *    slashes), must not be "." (no-op), and must not be ".." (no traversal
 *    above any expansion root or anywhere in an absolute path);
 *  - a NUL byte rejects outright (defensive in depth: [AgentCommandSpec]
 *    already makes such specs unconstructible);
 *  - components may contain any other character (spaces, quotes, `$`): those
 *    are legitimate path-name bytes and this policy does not pretend to
 *    know more than the filesystem does.
 *
 * This is a FORM check only. Whether the referenced file exists, is
 * executable, or is wise to run is decided by Termux at execution time — the
 * earlier validation tiers (hard blocks, sensitive tables, argument rules,
 * registry exact-shape matching) remain the authority for classification.
 */
object TermuxExecutablePath {

    /** The one documented Termux prefix-dir expansion token. */
    const val PREFIX_TOKEN: String = "\$PREFIX/"

    /** The one documented Termux home-dir expansion token. */
    const val HOME_TOKEN: String = "~/"

    /**
     * Returns null when [path] is an executable path form this backend
     * honestly supports, or a deterministic rejection detail describing the
     * first violated rule. Pure: never throws for operational causes.
     */
    fun errorFor(path: String): String? {
        if (path.contains('\u0000')) return "NUL byte in executable path"
        val remainder: String = when {
            path.startsWith("/") -> path.substring(1)
            path.startsWith(PREFIX_TOKEN) -> path.substring(PREFIX_TOKEN.length)
            path.startsWith(HOME_TOKEN) -> path.substring(HOME_TOKEN.length)
            else ->
                return "not an absolute path and not a documented Termux " +
                    "expansion prefix ($PREFIX_TOKEN or $HOME_TOKEN): '$path'"
        }
        for (component in remainder.split('/')) {
            when (component) {
                "" -> return "empty path component (repeated or trailing '/'): '$path'"
                "." -> return "no-op path component '.': '$path'"
                ".." -> return "parent-directory traversal '..': '$path'"
            }
        }
        return null
    }
}
