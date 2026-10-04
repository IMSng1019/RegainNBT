package regainnbt.detect;

import java.util.ArrayList;
import java.util.List;

/**
 * NBT / 文本组件「片段定位」：配对括号 + 引号感知的 O(n) 扫描。
 *
 * <p>硬性约束 2：<strong>不能用正则解析/替换 NBT</strong>。这里只负责把原始命令切成片段，
 * 真正的解析交给 {@link net.minecraft.nbt.TagParser#parseCompoundFully(String)}。
 *
 * <p>扫描规则：
 * <ul>
 *   <li>不在引号里的 <code>{</code> 起，按配对 <code>}</code> 吃掉整段复合标签（内部引号不当作片段边界）；</li>
 *   <li>不在复合标签里的 <code>'</code> / <code>"</code> 起，按配对引号吃掉一个字符串片段（支持 \ 转义）；</li>
 *   <li>同时记录片段所在的中括号深度（<code>[...]</code>）——顶层 vs 组件列表里的 {...} 含义不同。</li>
 * </ul>
 */
public final class NbtFragments {

	public enum Kind {
		COMPOUND,
		QUOTED
	}

	/**
	 * 一个片段。
	 *
	 * @param kind        COMPOUND（{@code {...}}）或 QUOTED（{@code '...'} / {@code "..."}）
	 * @param start       在命令里的起始下标（含）
	 * @param end         结束下标（不含）
	 * @param quote       QUOTED 时是引号字符
	 * @param squareDepth 该片段出现时的中括号深度（0 = 顶层）
	 * @param text        COMPOUND 时是包含花括号的原文；QUOTED 时是去掉首尾引号的原文
	 */
	public record Fragment(Kind kind, int start, int end, char quote, int squareDepth, String text) {
		public boolean compound() {
			return kind == Kind.COMPOUND;
		}

		public boolean quoted() {
			return kind == Kind.QUOTED;
		}
	}

	/** 单条命令最多扫描的片段数，防御性上限。 */
	private static final int MAX_FRAGMENTS = 128;

	private NbtFragments() {
	}

	/**
	 * O(n) 快速预筛：完全没有 {@code '{'} / {@code '['} / {@code nbt=} / 引号迹象时，
	 * 调用方可以立刻返回 MODERN（Router 每条命令都会调用本检测器）。
	 */
	public static boolean hasPossibleNbt(String command) {
		if (command == null || command.isEmpty()) return false;
		return command.indexOf('{') >= 0
				|| command.indexOf('[') >= 0
				|| command.indexOf('\'') >= 0
				|| command.indexOf('"') >= 0
				|| command.contains("nbt=");
	}

	/** 扫描整条命令，按出现顺序返回片段。 */
	public static List<Fragment> scan(String command) {
		List<Fragment> out = new ArrayList<>();
		if (command == null) return out;
		int i = 0;
		int n = command.length();
		int square = 0;
		while (i < n && out.size() < MAX_FRAGMENTS) {
			char c = command.charAt(i);
			if (c == '{') {
				int end = matchCompound(command, i);
				if (end < 0) {
					i++;
					continue;
				}
				out.add(new Fragment(Kind.COMPOUND, i, end, '\0', square, command.substring(i, end)));
				i = end;
			} else if (c == '\'' || c == '"') {
				int end = matchQuoted(command, i);
				if (end < 0) {
					i++;
					continue;
				}
				out.add(new Fragment(Kind.QUOTED, i, end, c, square, command.substring(i + 1, end - 1)));
				i = end;
			} else if (c == '[') {
				square++;
				i++;
			} else if (c == ']') {
				if (square > 0) square--;
				i++;
			} else {
				i++;
			}
		}
		return out;
	}

	/** 从 open 处的 <code>{</code> 找配对 <code>}</code>；返回结束下标（不含），失败 -1。 */
	public static int matchCompound(String s, int open) {
		if (open < 0 || open >= s.length() || s.charAt(open) != '{') return -1;
		int depth = 0;
		for (int i = open; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\'' || c == '"') {
				int end = matchQuoted(s, i);
				if (end < 0) return -1;
				i = end - 1;
			} else if (c == '{') {
				depth++;
			} else if (c == '}') {
				depth--;
				if (depth == 0) return i + 1;
			}
		}
		return -1;
	}

	/** 从 open 处的引号找配对引号（支持 \ 转义）；返回结束下标（不含），失败 -1。 */
	public static int matchQuoted(String s, int open) {
		if (open < 0 || open >= s.length()) return -1;
		char quote = s.charAt(open);
		if (quote != '\'' && quote != '"') return -1;
		for (int i = open + 1; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\\') {
				i++;
				continue;
			}
			if (c == quote) return i + 1;
		}
		return -1;
	}

	/** 取文本里第一个复合标签原文；没有返回 null。 */
	public static String firstCompound(String text) {
		if (text == null) return null;
		int open = text.indexOf('{');
		if (open < 0) return null;
		int end = matchCompound(text, open);
		return end < 0 ? null : text.substring(open, end);
	}

	/**
	 * 在任意文本里找 <code>key=</code> 后面的复合标签原文（例如选择器里的 <code>nbt={...}</code>）。
	 * 忽略出现在标识符内部的同名子串（如 <code>mynbt=</code>）。
	 */
	public static String compoundValueAfterKey(String text, String key) {
		if (text == null || key == null || key.isEmpty()) return null;
		int from = 0;
		while (from <= text.length()) {
			int idx = text.indexOf(key, from);
			if (idx < 0) return null;
			from = idx + key.length();
			if (idx > 0 && isIdentifierChar(text.charAt(idx - 1))) continue;
			if (from >= text.length() || text.charAt(from) != '=') continue;
			int open = from + 1;
			while (open < text.length() && Character.isWhitespace(text.charAt(open))) open++;
			if (open >= text.length() || text.charAt(open) != '{') continue;
			int end = matchCompound(text, open);
			if (end > 0) return text.substring(open, end);
		}
		return null;
	}

	/**
	 * 把 NBT 路径切成段：去掉 <code>[下标]</code> 与 <code>{过滤}</code>，支持引号段。
	 * 例：<code>HandItems[0].tag.display.Name</code> -> [HandItems, tag, display, Name]
	 */
	public static List<String> pathSegments(String path) {
		List<String> segments = new ArrayList<>();
		if (path == null || path.isEmpty()) return segments;
		StringBuilder current = new StringBuilder();
		int i = 0;
		while (i < path.length()) {
			char c = path.charAt(i);
			if (c == '[') {
				flush(segments, current);
				int depth = 1;
				i++;
				while (i < path.length() && depth > 0) {
					char x = path.charAt(i);
					if (x == '\'' || x == '"') {
						int end = matchQuoted(path, i);
						if (end < 0) {
							i++;
							continue;
						}
						i = end;
						continue;
					}
					if (x == '[') depth++;
					else if (x == ']') depth--;
					i++;
				}
			} else if (c == '{') {
				flush(segments, current);
				int end = matchCompound(path, i);
				i = end < 0 ? i + 1 : end;
			} else if (c == '\'' || c == '"') {
				flush(segments, current);
				int end = matchQuoted(path, i);
				if (end < 0) {
					i++;
					continue;
				}
				segments.add(path.substring(i + 1, end - 1));
				i = end;
			} else if (c == '.') {
				flush(segments, current);
				i++;
			} else {
				current.append(c);
				i++;
			}
		}
		flush(segments, current);
		return segments;
	}

	/** 按空白切词（命令 token；命令里未转义的空白一定是分隔符）。 */
	public static List<String> words(String s) {
		List<String> out = new ArrayList<>();
		if (s == null) return out;
		int i = 0;
		int n = s.length();
		while (i < n) {
			while (i < n && Character.isWhitespace(s.charAt(i))) i++;
			int start = i;
			while (i < n && !Character.isWhitespace(s.charAt(i))) i++;
			if (i > start) out.add(s.substring(start, i));
		}
		return out;
	}

	public static boolean isIdentifierChar(char c) {
		return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == ':' || c == '.';
	}

	private static void flush(List<String> segments, StringBuilder current) {
		if (current.length() > 0) {
			segments.add(current.toString());
			current.setLength(0);
		}
	}
}
