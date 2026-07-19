package com.costpilot.cli;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

@Command(name = "approvals", description = "List and act on pending approval requests.",
		subcommands = { ApprovalsCommand.Ls.class, ApprovalsCommand.Approve.class, ApprovalsCommand.Reject.class })
public class ApprovalsCommand {

	@ParentCommand
	CostpilotCli parent;

	@Command(name = "ls", description = "List pending approval requests.")
	static class Ls implements Callable<Integer> {
		@ParentCommand
		ApprovalsCommand approvals;

		@Override
		public Integer call() {
			System.out.println(approvals.parent.client().get("/admin/approvals"));
			return 0;
		}
	}

	@Command(name = "approve", description = "Approve a pending request; it is then forwarded and billed.")
	static class Approve implements Callable<Integer> {
		@ParentCommand
		ApprovalsCommand approvals;

		@Parameters(index = "0", description = "the pending approval id")
		String id;

		@Override
		public Integer call() {
			System.out.println(approvals.parent.client().post("/admin/approvals/" + id + "/approve", null));
			return 0;
		}
	}

	@Command(name = "reject", description = "Reject a pending request; it is never forwarded.")
	static class Reject implements Callable<Integer> {
		@ParentCommand
		ApprovalsCommand approvals;

		@Parameters(index = "0", description = "the pending approval id")
		String id;

		@Option(names = "--reason", description = "why the request was rejected")
		String reason;

		@Override
		public Integer call() {
			String body = reason != null ? "{\"reason\":\"" + reason + "\"}" : null;
			System.out.println(approvals.parent.client().post("/admin/approvals/" + id + "/reject", body));
			return 0;
		}
	}
}
