package com.costpilot.cli;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

@Command(name = "policy", description = "Manage policy rules.",
		subcommands = { PolicyCommand.Set.class, PolicyCommand.Ls.class, PolicyCommand.Rm.class })
public class PolicyCommand {

	@ParentCommand
	CostpilotCli parent;

	@Command(name = "set", description = "Create or update a policy rule (takes effect immediately).")
	static class Set implements Callable<Integer> {
		@ParentCommand
		PolicyCommand policy;

		@Option(names = "--scope-type", required = true, description = "team | project")
		String scopeType;

		@Option(names = "--scope-ref", required = true)
		String scopeRef;

		@Option(names = "--allowed", required = true,
				description = "comma-separated allowed models, e.g. \"gpt-4o-mini,claude-*\"")
		String allowedModels;

		@Option(names = "--fallback", required = true, description = "deny | downgrade | require_approval")
		String fallbackAction;

		@Option(names = "--downgrade-to", description = "target model when fallback = downgrade")
		String downgradeTo;

		@Option(names = "--approval-threshold-nanos",
				description = "cost gate: requests estimated above this (nanodollars) need approval")
		Long approvalThresholdNanos;

		@Override
		public Integer call() {
			StringBuilder body = new StringBuilder("{")
					.append("\"scopeType\":\"").append(scopeType).append("\",")
					.append("\"scopeRef\":\"").append(scopeRef).append("\",")
					.append("\"allowedModels\":\"").append(allowedModels).append("\",")
					.append("\"fallbackAction\":\"").append(fallbackAction).append("\"");
			if (downgradeTo != null) {
				body.append(",\"downgradeTo\":\"").append(downgradeTo).append("\"");
			}
			if (approvalThresholdNanos != null) {
				body.append(",\"approvalThresholdNanos\":").append(approvalThresholdNanos);
			}
			body.append("}");
			System.out.println(policy.parent.client().put("/admin/policies", body.toString()));
			return 0;
		}
	}

	@Command(name = "ls", description = "List policy rules.")
	static class Ls implements Callable<Integer> {
		@ParentCommand
		PolicyCommand policy;

		@Override
		public Integer call() {
			System.out.println(policy.parent.client().get("/admin/policies"));
			return 0;
		}
	}

	@Command(name = "rm", description = "Deactivate a policy rule.")
	static class Rm implements Callable<Integer> {
		@ParentCommand
		PolicyCommand policy;

		@Option(names = "--scope-type", required = true)
		String scopeType;

		@Option(names = "--scope-ref", required = true)
		String scopeRef;

		@Override
		public Integer call() {
			policy.parent.client().delete("/admin/policies?scopeType=" + scopeType + "&scopeRef=" + scopeRef);
			System.out.println("deactivated policy " + scopeType + ":" + scopeRef);
			return 0;
		}
	}
}
