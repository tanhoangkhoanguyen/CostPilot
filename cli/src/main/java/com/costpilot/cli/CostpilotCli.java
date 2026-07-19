package com.costpilot.cli;

import com.fasterxml.jackson.databind.ObjectMapper;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * 9.3: the costpilot admin CLI. Ops-friendly control of the gateway without a frontend -
 * wraps the admin + approvals API (budget/policy set, approvals ls/approve/reject, spend
 * show). Endpoint and admin key come from options or the COSTPILOT_ENDPOINT /
 * COSTPILOT_ADMIN_KEY env vars. Non-zero exit on any error.
 */
@Command(name = "costpilot",
		mixinStandardHelpOptions = true,
		version = "costpilot 0.0.1",
		description = "Admin CLI for the CostPilot governance gateway.",
		subcommands = {
				BudgetCommand.class,
				PolicyCommand.class,
				ApprovalsCommand.class,
				SpendCommand.class
		})
public class CostpilotCli {

	static final ObjectMapper MAPPER = new ObjectMapper();

	@Option(names = "--endpoint",
			description = "Gateway base URL (default: $COSTPILOT_ENDPOINT or http://localhost:8080).")
	private String endpoint;

	@Option(names = "--admin-key",
			description = "Admin API key (default: $COSTPILOT_ADMIN_KEY).")
	private String adminKey;

	GatewayClient client() {
		String url = endpoint != null ? endpoint
				: envOr("COSTPILOT_ENDPOINT", "http://localhost:8080");
		String key = adminKey != null ? adminKey : System.getenv("COSTPILOT_ADMIN_KEY");
		if (key == null || key.isBlank()) {
			throw new CommandLine.ParameterException(new CommandLine(this),
					"admin key required: pass --admin-key or set COSTPILOT_ADMIN_KEY");
		}
		return new GatewayClient(url, key);
	}

	private static String envOr(String name, String fallback) {
		String value = System.getenv(name);
		return value != null && !value.isBlank() ? value : fallback;
	}

	public static void main(String[] args) {
		int exit = new CommandLine(new CostpilotCli())
				.setExecutionExceptionHandler((ex, cmd, parseResult) -> {
					cmd.getErr().println(cmd.getColorScheme().errorText(ex.getMessage()));
					return cmd.getExitCodeExceptionMapper() != null
							? cmd.getExitCodeExceptionMapper().getExitCode(ex)
							: cmd.getCommandSpec().exitCodeOnExecutionException();
				})
				.execute(args);
		System.exit(exit);
	}
}
