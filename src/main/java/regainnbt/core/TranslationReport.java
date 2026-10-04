package regainnbt.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次翻译过程的诊断记录，供日志与 /regainnbt why 使用。
 * 非线程安全：每次路由创建一个实例，只在当次调用链内使用。
 */
public final class TranslationReport {
	private final String original;
	private PathKind path = PathKind.NONE;
	private final List<String> steps = new ArrayList<>();
	private final List<String> warnings = new ArrayList<>();
	private String translated;
	private boolean adopted;

	public TranslationReport(String original) {
		this.original = original;
	}

	public String original() {
		return original;
	}

	public PathKind path() {
		return path;
	}

	public void path(PathKind path) {
		this.path = path;
	}

	/** 记录一步成功的关键过程（会显示在 /regainnbt why 里）。 */
	public void step(String message) {
		steps.add(message);
	}

	/** 记录一条需要管理员注意的问题（缺口、未验证、规则失败等）。 */
	public void warn(String message) {
		warnings.add(message);
	}

	public List<String> steps() {
		return Collections.unmodifiableList(steps);
	}

	public List<String> warnings() {
		return Collections.unmodifiableList(warnings);
	}

	public String translated() {
		return translated;
	}

	public void translated(String translated) {
		this.translated = translated;
	}

	/** 是否「验证后采用」（reparse 通过且关键结果确实出现）。 */
	public boolean adopted() {
		return adopted;
	}

	public void adopted(boolean adopted) {
		this.adopted = adopted;
	}

	public String summary() {
		StringBuilder sb = new StringBuilder();
		sb.append(path).append(" adopted=").append(adopted);
		if (!steps.isEmpty()) {
			sb.append(" steps=").append(steps);
		}
		if (!warnings.isEmpty()) {
			sb.append(" warnings=").append(warnings);
		}
		return sb.toString();
	}
}
