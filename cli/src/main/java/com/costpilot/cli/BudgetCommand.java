package com.costpilot.cli;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

@Command(name = "budget", description = "Manage budgets.",
		subcommands = { BudgetCommand.Set.class, BudgetCommand.Ls.class, BudgetCommand.Rm.class })
public class BudgetCommand {

	@ParentCommand
	CostpilotCli parent;

	@Command(name = "set", description = "Create or update a budget limit (takes effect immediately).")
	static class Set implements Callable<Integer> {
		@ParentCommand
		BudgetCommand budget;

		@Option(names = "--scope", required = true, description = "tenant | team | project | model")
		String scope;

		@Option(names = "--ref", required = true, description = "the scope reference (e.g. team id)")
		String ref;

		@Option(names = "--limit", required = true, description = "dollar limit, e.g. 25.00")
		String limit;

		@Override
		public Integer call() {
			String body = "{\"scope\":\"%s\",\"ref\":\"%s\",\"limit\":%s}".formatted(scope, ref, limit);
			System.out.println(budget.parent.client().put("/admin/budgets", body));
			return 0;
		}
	}

	@Command(name = "ls", description = "List budgets.")
	static class Ls implements Callable<Integer> {
		@ParentCommand
		BudgetCommand budget;

		@Override
		public Integer call() {
			System.out.println(budget.parent.client().get("/admin/budgets"));
			return 0;
		}
	}

	@Command(name = "rm", description = "Deactivate a budget.")
	static class Rm implements Callable<Integer> {
		@ParentCommand
		BudgetCommand budget;

		@Option(names = "--scope", required = true)
		String scope;

		@Option(names = "--ref", required = true)
		String ref;

		@Override
		public Integer call() {
			budget.parent.client().delete("/admin/budgets?scope=" + scope + "&ref=" + ref);
			System.out.println("deactivated budget " + scope + ":" + ref);
			return 0;
		}
	}
}
