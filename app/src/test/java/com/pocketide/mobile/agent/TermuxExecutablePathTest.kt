package com.pocketide.mobile.agent

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-level form contract of the Termux backend executable-path policy
 * ([TermuxExecutablePath]), the trusted-code enforcement of the
 * [AgentCommandSpec.executable] absoluteness/outcome contract deferred by
 * T1 and bound to the concrete backend at dispatch time.
 *
 * Every accepted/rejected case below corresponds to one documented Termux
 * RUN_COMMAND-path fact or one structural component rule; nothing here
 * encodes taste beyond fail-closed honesty toward undocumented forms.
 */
class TermuxExecutablePathTest {

    private fun accepted(path: String) {
        assertNull(
            "expected accept for '$path'",
            TermuxExecutablePath.errorFor(path)
        )
    }

    private fun rejectedForReason(path: String, detailMustContain: String) {
        val detail = TermuxExecutablePath.errorFor(path)
        assertTrue(
            "expected reject for '$path', got null",
            detail != null
        )
        assertTrue(
            "rejection detail for '$path' should mention '$detailMustContain': $detail",
            detail!!.contains(detailMustContain)
        )
    }

    // ---- forms the Termux wiki documents ----

    @Test
    fun `absolute path is accepted`() = accepted("/bin/top")

    @Test
    fun `deep absolute termux binary path is accepted`() =
        accepted("/data/data/com.termux/files/usr/bin/ls")

    @Test
    fun `PREFIX expansion token is accepted`() = accepted("\$PREFIX/bin/top")

    @Test
    fun `nested PREFIX expansion path is accepted`() = accepted("\$PREFIX/a/b/c")

    @Test
    fun `home tilde expansion token is accepted`() = accepted("~/bin/x")

    @Test
    fun `nested tilde expansion path is accepted`() = accepted("~/a/b")

    @Test
    fun `token-named component inside an expansion remainder is a legal literal name`() =
        accepted("\$PREFIX/\$PREFIX")

    @Test
    fun `components may contain spaces quotes and dollar signs`() =
        accepted("/bin/a b'c\$d")

    // ---- undocumented tokens fail closed ----

    @Test
    fun `HOME dollar token is not a documented RUN_COMMAND path prefix`() =
        rejectedForReason("\$HOME/bin/top", "expansion prefix")

    @Test
    fun `bare PREFIX without slash is not documented`() =
        rejectedForReason("\$PREFIX", "expansion prefix")

    @Test
    fun `flag-shaped PREFIXECHO name does not reach the token branch`() =
        rejectedForReason("\$PREFIXECHO/x", "expansion prefix")

    @Test
    fun `unknown dollar token is rejected`() =
        rejectedForReason("\$FOO/x", "expansion prefix")

    @Test
    fun `bare tilde alone is rejected`() =
        rejectedForReason("~", "expansion prefix")

    @Test
    fun `other-user tilde form is shell semantics not a service token and is rejected`() =
        rejectedForReason("~user/tool", "expansion prefix")

    // ---- relative paths fail closed ----

    @Test
    fun `relative path is rejected`() =
        rejectedForReason("bin/top", "not an absolute path")

    @Test
    fun `dot-relative path is rejected`() =
        rejectedForReason("./bin/top", "not an absolute path")

    @Test
    fun `empty path is rejected`() =
        rejectedForReason("", "not an absolute path")

    @Test
    fun `blank path is rejected`() =
        rejectedForReason(" ", "not an absolute path")

    @Test
    fun `token embedded mid-name is just a relative path`() =
        rejectedForReason("a\$PREFIX/b", "not an absolute path")

    // ---- component structure rules ----

    @Test
    fun `trailing slash on an absolute path is an empty component`() =
        rejectedForReason("/bin/", "empty path component")

    @Test
    fun `trailing slash after PREFIX is an empty component`() =
        rejectedForReason("\$PREFIX/", "empty path component")

    @Test
    fun `trailing slash after tilde is an empty component`() =
        rejectedForReason("~/", "empty path component")

    @Test
    fun `repeated slash inside an absolute path is an empty component`() =
        rejectedForReason("/bin//echo", "empty path component")

    @Test
    fun `leading double slash is an empty component`() =
        rejectedForReason("//bin/echo", "empty path component")

    @Test
    fun `repeated slash inside an expansion remainder is an empty component`() =
        rejectedForReason("\$PREFIX//bin", "empty path component")

    @Test
    fun `no-op component inside an absolute path is rejected`() =
        rejectedForReason("/bin/./echo", "no-op path component")

    @Test
    fun `traversal inside an absolute path is rejected`() =
        rejectedForReason("/bin/../bin/echo", "traversal")

    @Test
    fun `traversal above the PREFIX root is rejected`() =
        rejectedForReason("\$PREFIX/../usr/bin/ls", "traversal")

    @Test
    fun `traversal above the tilde root is rejected`() =
        rejectedForReason("~/../below", "traversal")

    @Test
    fun `traversal as the only remainder component is rejected`() =
        rejectedForReason("\$PREFIX/..", "traversal")

    // ---- structural invariants ----

    @Test
    fun `NUL byte in an absolute path rejects outright`() =
        rejectedForReason("/bi\u0000n/top", "NUL")

    @Test
    fun `NUL byte inside an expansion remainder rejects outright`() =
        rejectedForReason("\$PREFIX/a\u0000b", "NUL")

    // ---- determinism ----

    @Test
    fun `pure function is stable across repeated evaluation`() {
        val sample = "/bin/../bin/echo"
        val first = TermuxExecutablePath.errorFor(sample)
        val second = TermuxExecutablePath.errorFor(sample)
        assertTrue(first != null)
        assertTrue(first == second)
        assertNull(TermuxExecutablePath.errorFor("/bin/echo"))
        assertNull(TermuxExecutablePath.errorFor("/bin/echo"))
    }
}
