package regainnbt.detect;

/** 意图检测结论。 */
public record IntentVerdict(boolean legacy, String reason) {

	public static final IntentVerdict MODERN = new IntentVerdict(false, "");

	public static IntentVerdict legacy(String reason) {
		return new IntentVerdict(true, reason);
	}

	public static IntentVerdict modern(String reason) {
		return new IntentVerdict(false, reason);
	}
}
