package regainnbt.detect;

import java.util.LinkedHashSet;
import java.util.List;

/** 意图检测结论。 */
public record IntentVerdict(boolean legacy, String reason) {

	public static final IntentVerdict MODERN = new IntentVerdict(false, "");

	/** 多条命中原因之间的分隔符（会原样显示在 /regainnbt why 里）。 */
	public static final String REASON_SEPARATOR = " | ";

	public static IntentVerdict legacy(String reason) {
		return new IntentVerdict(true, reason);
	}

	public static IntentVerdict modern(String reason) {
		return new IntentVerdict(false, reason);
	}

	/** 由若干条命中原因合成结论：没有命中就是 {@link #MODERN}，命中则按出现顺序去重拼接。 */
	public static IntentVerdict of(List<String> hits) {
		if (hits == null || hits.isEmpty()) return MODERN;
		LinkedHashSet<String> unique = new LinkedHashSet<>(hits);
		return new IntentVerdict(true, String.join(REASON_SEPARATOR, unique));
	}
}
