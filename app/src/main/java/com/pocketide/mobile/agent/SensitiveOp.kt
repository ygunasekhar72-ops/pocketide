package com.pocketide.mobile.agent

/**
 * Model-independent vocabulary for what a proposed agent command is allowed
 * to touch. This enum is the *unit of policy*, not a trust marker:
 *
 *  - `AgentCommandSpec.sensitiveOpsHints` carries a model/advisor-supplied
 *    classification which is ADVISORY ONLY. Future execution policy (an
 *    application-owned gate, not the model) must independently classify each
 *    command and intersect its own decision with any hints before dispatch.
 *  - No code path in T1 reads or trusts hints; they exist so the contract can
 *    carry them without redefining the type later.
 *
 * Members are intentionally coarse (auditable categories, not per-tool flags).
 */
enum class SensitiveOp {

    /** Creating or modifying files inside an approved workspace. */
    WRITE_PROJECT_FILES,

    /** Reaching the network from the executed tool (curl, git push, pip). */
    NETWORK_EGRESS,

    /** Spawning or signalling other processes beyond the command itself. */
    PROCESS_MANAGEMENT,

    /** Installing, removing, or upgrading tool packages (apt/pip/pkg). */
    PACKAGE_MANAGEMENT,

    /** Anything requiring elevated or sandbox-escalating privileges (su, pm grant). */
    PRIVILEGED_EXECUTION
}
