package com.wingedsheep.rundiagnostics.supervisor

/** CLI entry point for external observation only; it never terminates the target process. */
public fun main(args: Array<String>) {
    if (args.any { it == "--help" || it == "-h" }) {
        println(SupervisorCli.usage())
        return
    }
    val config = try {
        SupervisorCli.parse(args)
    } catch (_: SupervisorCliException) {
        System.err.println("SUPERVISOR_CONFIG_INVALID")
        return
    }
    val supervisor = ExternalSupervisor(config)
    try {
        val lastPoll = supervisor.run().lastPoll
        if (lastPoll == null) {
            println("SUPERVISOR_NO_OBSERVATION")
        } else {
            println(
                "classification=${lastPoll.decision.classification} " +
                    "trigger=${lastPoll.decision.trigger} " +
                    "action=${lastPoll.decision.action}",
            )
        }
    } finally {
        supervisor.close()
    }
}
