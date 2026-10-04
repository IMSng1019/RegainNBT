package regainnbt.translate;

import java.util.ArrayList;
import java.util.List;

/**
 * 配对括号 / 引号感知的片段定位工具。
 *
 * <p>硬性约束 2：<b>禁止用正则解析或替换 NBT</b>。本类只做「按字符扫描」：
 * 单双引号 + 反斜杠转义、{@code {}} 嵌套、{@code []} 嵌套（含 {@code [I;1,2,3]} 数组）、
 * 以及选择器 {@code @e[...]} 里的选项区。
 */
final class SnbtScanner {

	private SnbtScanner() {
	}

	/** {@code open} 位置必须是 '{' 或 '['；返回配对闭合括号的下标；找不到返回 -1。 */
	static int matchBracket(String s, int open) {
		if (open < 0 || open >= s.length()) {
			return -1;
		}
		char openChar = s.charAt(open);
		if (openChar != '{' && openChar != '[') {
			return -1;
		}
		int depth = 0;
		int i = open;
		while (i < s.length()) {
			char c = s.charAt(i);
			if (c == '\'' || c == '"') {
				int close = skipQuoted(s, i);
				if (close < 0) {
					return -1;
				}
				i = close + 1;
				continue;
			}
			if (c == '{' || c == '[') {
				depth++;
			} else if (c == '}' || c == ']') {
				depth--;
				if (depth == 0) {
					return i;
				}
			}
			i++;
		}
		return -1;
	}

	/** {@code start} 必须是引号；返回闭合引号下标（处理反斜杠转义）；未闭合返回 -1。 */
	static int skipQuoted(String s, int start) {
		char quote = s.charAt(start);
		int i = start + 1;
		while (i < s.length()) {
			char c = s.charAt(i);
			if (c == '\\') {
				i += 2;
				continue;
			}
			if (c == quote) {
				return i;
			}
			i++;
		}
		return -1;
	}

	/** 从 {@code start} 起扫描一个命令 token：遇到顶层空白结束；引号与括号内部不切分。 */
	static int scanTokenEnd(String s, int start) {
		int i = start;
		while (i < s.length()) {
			char c = s.charAt(i);
			if (c == '\'' || c == '"') {
				int close = skipQuoted(s, i);
				if (close < 0) {
					return s.length();
				}
				i = close + 1;
				continue;
			}
			if (c == '{' || c == '[') {
				int close = matchBracket(s, i);
				if (close < 0) {
					return s.length();
				}
				i = close + 1;
				continue;
			}
			if (Character.isWhitespace(c)) {
				return i;
			}
			i++;
		}
		return i;
	}

	/** 顶层 token 的 [start, end) 列表（含引号/括号整体）。 */
	static List<int[]> tokens(String s, int from, int to) {
		List<int[]> out = new ArrayList<>();
		int i = Math.max(0, from);
		int limit = Math.min(to, s.length());
		while (i < limit) {
			while (i < limit && Character.isWhitespace(s.charAt(i))) {
				i++;
			}
			if (i >= limit) {
				break;
			}
			int end = scanTokenEnd(s, i);
			out.add(new int[] { i, end });
			i = end;
		}
		return out;
	}

	/** 在顶层查找等于 {@code token} 的 token，返回 [start, end)，找不到返回 null。 */
	static int[] findTopLevelToken(String s, String token, int from, int to) {
		for (int[] t : tokens(s, from, to)) {
			if (s.substring(t[0], t[1]).equals(token)) {
				return t;
			}
		}
		return null;
	}

	/** 在顶层查找第一个等于 {@code c} 的字符（不在引号/括号内），找不到返回 -1。 */
	static int firstTopLevelChar(String s, char c) {
		int depth = 0;
		int i = 0;
		while (i < s.length()) {
			char ch = s.charAt(i);
			if (ch == '\'' || ch == '"') {
				int close = skipQuoted(s, i);
				if (close < 0) {
					return -1;
				}
				i = close + 1;
				continue;
			}
			if (ch == '{' || ch == '[') {
				if (depth == 0 && ch == c) {
					return i;
				}
				depth++;
			} else if (ch == '}' || ch == ']') {
				depth--;
			} else if (depth == 0 && ch == c) {
				return i;
			}
			i++;
		}
		return -1;
	}

	/** 去掉外层引号并还原反斜杠转义；不是引号串时原样返回。 */
	static String unquote(String s) {
		String t = s.trim();
		if (t.length() >= 2) {
			char q = t.charAt(0);
			if ((q == '\'' || q == '"') && t.charAt(t.length() - 1) == q) {
				return unescape(t.substring(1, t.length() - 1));
			}
		}
		return t;
	}

	static boolean isQuoted(String s) {
		String t = s.trim();
		if (t.length() < 2) {
			return false;
		}
		char q = t.charAt(0);
		return (q == '\'' || q == '"') && t.charAt(t.length() - 1) == q;
	}

	private static String unescape(String s) {
		if (s.indexOf('\\') < 0) {
			return s;
		}
		StringBuilder sb = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\\' && i + 1 < s.length()) {
				char n = s.charAt(++i);
				switch (n) {
					case 'n' -> sb.append('\n');
					case 't' -> sb.append('\t');
					default -> sb.append(n);
				}
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}
}
