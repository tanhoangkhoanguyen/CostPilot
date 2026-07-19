package com.costpilot.cli;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

@Command(name = "spend", description = "Query spend analytics.",
		subcommands = { SpendCommand.Show.class })
public class SpendCommand {

	@ParentCommand
	CostpilotCli parent;

	@Command(name = "show", description = "Show spend, grouped by a dimension.")
	static class Show implements Callable<Integer> {
		@ParentCommand
		SpendCommand spend;

		@Option(names = "--group-by", defaultValue = "team",
				description = "group spend by: team | project | model (default: team)")
		String groupBy;

		@Override
		public Integer call() {
			System.out.println(spend.parent.client().get("/api/analytics/spend?groupBy=" + groupBy));
			return 0;
		}
	}
}
