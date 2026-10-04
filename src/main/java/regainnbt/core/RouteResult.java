package regainnbt.core;

/**
 * 路由结果：要么原样放行（{@link Decision#MODERN}），要么给出一条应当改执行的命令。
 */
public record RouteResult(Decision decision, PathKind path, String command, TranslationReport report) {

	public static RouteResult passThrough(String command) {
		return new RouteResult(Decision.MODERN, PathKind.NONE, command, new TranslationReport(command));
	}

	public static RouteResult passThrough(String command, PathKind path, TranslationReport report) {
		return new RouteResult(Decision.MODERN, path, command, report);
	}

	public boolean shouldReexecute() {
		return decision == Decision.TRANSLATED && command != null;
	}
}
