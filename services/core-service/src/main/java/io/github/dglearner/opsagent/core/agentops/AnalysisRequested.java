package io.github.dglearner.opsagent.core.agentops;

import io.github.dglearner.opsagent.contracts.AgentTask;

/** An analysis was recorded as pending; its task goes to the agent once the transaction committed. */
record AnalysisRequested(AgentTask task) {
}
