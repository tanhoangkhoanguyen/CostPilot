package com.costpilot.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;

class CostpilotCliTest {

	private CommandLine cli() {
		return new CommandLine(new CostpilotCli());
	}

	@Test
	void helpListsEveryTopLevelCommand() {
		StringWriter out = new StringWriter();
		CommandLine cli = cli();
		cli.setOut(new PrintWriter(out));
		int code = cli.execute("--help");
		assertEquals(0, code);
		String help = out.toString();
		assertTrue(help.contains("budget"), help);
		assertTrue(help.contains("policy"), help);
		assertTrue(help.contains("approvals"), help);
		assertTrue(help.contains("spend"), help);
	}

	@Test
	void subcommandsAreWired() {
		// budget/policy/approvals/spend each parse their subcommands without error
		assertTrue(cli().getSubcommands().get("budget").getSubcommands().containsKey("set"));
		assertTrue(cli().getSubcommands().get("policy").getSubcommands().containsKey("set"));
		assertTrue(cli().getSubcommands().get("approvals").getSubcommands().containsKey("approve"));
		assertTrue(cli().getSubcommands().get("approvals").getSubcommands().containsKey("reject"));
		assertTrue(cli().getSubcommands().get("spend").getSubcommands().containsKey("show"));
	}

	@Test
	void missingRequiredOptionExitsNonZero() {
		StringWriter err = new StringWriter();
		CommandLine cli = cli();
		cli.setErr(new PrintWriter(err));
		// budget set without --scope/--ref/--limit is a usage error
		int code = cli.execute("budget", "set");
		assertNotEquals(0, code);
	}

	@Test
	void missingAdminKeyExitsNonZero() {
		StringWriter err = new StringWriter();
		CommandLine cli = cli();
		cli.setErr(new PrintWriter(err));
		// all required options present, but no --admin-key and (in CI) no env var -> error
		// before any network call. Guard against a developer machine that has the env set.
		if (System.getenv("COSTPILOT_ADMIN_KEY") != null) {
			return;
		}
		int code = cli.execute("budget", "set", "--scope", "team", "--ref", "x", "--limit", "1.00");
		assertNotEquals(0, code);
	}
}
