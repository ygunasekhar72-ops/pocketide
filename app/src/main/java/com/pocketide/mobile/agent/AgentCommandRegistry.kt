package com.pocketide.mobile.agent

/**
 * Trusted declaration of ONE agent command template the application is
 * prepared to validate for future execution backends. Templates are created
 * by trusted application code only (never reconstructed from model text);
 * proposals are matched against templates by identity, not by shell-string
 * parsing, so no backend ever needs to concatenate arguments.
 *
 * T2 ships no default templates: an empty registry rejects every proposal.
 * The application supports only what trusted code actually registers here,
 * which keeps the registry honest about real backend capabilities.
 */
data class AgentCommandTemplate(
    val catalogId: String,
    val executable: String,
    val templateArgs: List<String> = emptyList(),
    val allowExtraArgs: Boolean = false
) {
    init {
        require(catalogId.isNotBlank()) { "catalogId must be non-blank" }
        require(executable.isNotEmpty() && !executable.contains(NUL)) {
            "executable must be non-empty and NUL-free"
        }
        require(templateArgs.none { it.contains(NUL) }) { "template argument must be NUL-free" }
    }

    private companion object {
        const val NUL = '\u0000'
    }
}

enum class ProposalRejectionReason {
    /** No template is registered under the proposal's catalogId. */
    UNKNOWN_COMMAND,

    /** The proposal's executable differs from the registered template. */
    EXECUTABLE_MISMATCH,

    /** The proposal's argument list does not satisfy the template's arg policy. */
    ARGUMENT_MISMATCH
}

sealed interface ProposalDecision {

    /** Structurally valid proposal for a registered command. Dispatch is NOT authorized by this. */
    data class Valid(
        val template: AgentCommandTemplate,
        val spec: AgentCommandSpec
    ) : ProposalDecision

    data class Invalid(
        val reason: ProposalRejectionReason,
        val detail: String
    ) : ProposalDecision {
        init {
            require(detail.isNotBlank()) { "rejection detail must be non-blank" }
        }
    }
}

/**
 * Registry of [AgentCommandTemplate]s, independent of any transport (Termux
 * sender, native PTY) and of approval policy. It only validates structure.
 *
 * Deterministic behavior:
 *  - unknown catalogId, executable mismatch, or argument-policy violation in
 *    a proposal → [ProposalDecision.Invalid] with a fixed reason, and the
 *    registry state is untouched;
 *  - duplicate registration of the same catalogId → IllegalArgumentException,
 *    always, even for an identical template; the first registration wins and
 *    is never replaced;
 *  - malformed templates are rejected at construction (data class require),
 *    so an invalid template can never enter the registry.
 *
 * IMMUTABILITY STRATEGY: Kotlin `List` alone does NOT guarantee immutability,
 * so the registry stores a defensive snapshot (fresh `toList()` copy) of
 * every registered template and hands out only snapshots through every
 * public surface ([lookup], [ProposalDecision.Valid]). The internal map is
 * private, so no caller can mutate the stored validation state through any
 * alias: mutating a constructed template, a looked-up template, or a
 * decision-attached template after the fact cannot change registry behavior.
 * The map instance itself is not thread-safe (same single-threaded contract
 * as the decision ledger).
 *
 * BINDING NOTE: templates bind catalogId ↔ executable ↔ argument policy. A
 * catalogId therefore cannot be reused for a different command shape through
 * this registry. What the registry does NOT bind: stdin, workdir, background
 * and transport-only spec fields — payload and path constraints belong to the
 * chosen backend and are validated there (T3), not here.
 */
class AgentCommandRegistry {

    private val templates = LinkedHashMap<String, AgentCommandTemplate>()

    val size: Int
        get() = templates.size

    /**
     * Registers a template. Throws on duplicate catalogId; never mutates on
     * failure. The registry stores a defensive copy; the caller's instance
     * becomes irrelevant to later validation.
     */
    fun register(template: AgentCommandTemplate) {
        val snapshot = template.copy(templateArgs = template.templateArgs.toList())
        require(templates.putIfAbsent(snapshot.catalogId, snapshot) == null) {
            "duplicate catalogId registration: '${snapshot.catalogId}'"
        }
    }

    /** Lookup by trusted catalogId; null for any unregistered id. Returns a snapshot. */
    fun lookup(catalogId: String): AgentCommandTemplate? =
        templates[catalogId]?.let { it.copy(templateArgs = it.templateArgs.toList()) }

    /**
     * Validates a proposal against the template registered under its
     * catalogId. Pure: repeated equivalent proposals yield equal decisions
     * and never change registry state. The Valid decision carries a snapshot
     * of the registered template, never the internal instance.
     */
    fun validateProposal(spec: AgentCommandSpec): ProposalDecision {
        val template = templates[spec.catalogId]
            ?: return ProposalDecision.Invalid(
                ProposalRejectionReason.UNKNOWN_COMMAND,
                "no command registered for catalogId='${spec.catalogId}'"
            )
        if (spec.executable != template.executable) {
            return ProposalDecision.Invalid(
                ProposalRejectionReason.EXECUTABLE_MISMATCH,
                "proposal executable '${spec.executable}' does not match registered " +
                    "'${template.executable}' for catalogId='${template.catalogId}'"
            )
        }
        val declared = template.templateArgs
        val supplied = spec.args
        if (template.allowExtraArgs) {
            val wrong = supplied.size < declared.size ||
                supplied.subList(0, declared.size) != declared

            if (wrong) {
                return ProposalDecision.Invalid(
                    ProposalRejectionReason.ARGUMENT_MISMATCH,
                    "proposal args ${spec.args} do not begin with the registered args $declared"
                )
            }
        } else if (spec.args != declared) {
            return ProposalDecision.Invalid(
                ProposalRejectionReason.ARGUMENT_MISMATCH,
                "proposal args ${spec.args} do not match registered args $declared"
            )
        }
        return ProposalDecision.Valid(
            template.copy(templateArgs = template.templateArgs.toList()),
            spec
        )
    }
}
