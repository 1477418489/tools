package plugin.javafxtools.control;

/**
 * 日志文本的线性、忽略大小写搜索算法。
 */
public final class LogSearchSupport {
    private LogSearchSupport() {
    }

    public static int countMatches(String text, String query) {
        return summarize(text, query, -1).matchCount();
    }

    public static int findNext(String text, String query, int fromIndex) {
        return findNextWithStats(text, query, fromIndex).matchIndex();
    }

    public static int findPrevious(String text, String query, int fromIndex) {
        return findPreviousWithStats(text, query, fromIndex).matchIndex();
    }

    public static int matchOrdinal(String text, String query, int matchIndex) {
        if (!canSearch(text, query) || matchIndex < 0) {
            return 0;
        }

        int ordinal = 0;
        PatternData pattern = createPattern(query);
        MatchIterator iterator = new MatchIterator(text, pattern);
        int index;
        while ((index = iterator.next()) >= 0 && index <= matchIndex) {
            ordinal++;
        }
        return ordinal;
    }

    public static SearchSummary summarize(String text, String query, int selectedIndex) {
        if (!canSearch(text, query)) {
            return SearchSummary.EMPTY;
        }

        PatternData pattern = createPattern(query);
        MatchIterator iterator = new MatchIterator(text, pattern);
        int count = 0;
        int firstIndex = -1;
        int selectedOrdinal = 0;
        int index;
        while ((index = iterator.next()) >= 0) {
            count++;
            if (firstIndex < 0) {
                firstIndex = index;
            }
            if (index == selectedIndex) {
                selectedOrdinal = count;
            }
        }
        return new SearchSummary(count, firstIndex, selectedOrdinal);
    }

    public static SearchResult findNextWithStats(String text, String query, int fromIndex) {
        if (!canSearch(text, query)) {
            return SearchResult.EMPTY;
        }

        int lastStart = text.length() - query.length();
        int start = Math.max(0, Math.min(fromIndex, lastStart + 1));
        PatternData pattern = createPattern(query);
        MatchIterator iterator = new MatchIterator(text, pattern);
        int count = 0;
        int firstIndex = -1;
        int selectedIndex = -1;
        int selectedOrdinal = 0;
        int index;
        while ((index = iterator.next()) >= 0) {
            count++;
            if (firstIndex < 0) {
                firstIndex = index;
            }
            if (selectedIndex < 0 && index >= start) {
                selectedIndex = index;
                selectedOrdinal = count;
            }
        }
        if (selectedIndex < 0 && firstIndex >= 0) {
            selectedIndex = firstIndex;
            selectedOrdinal = 1;
        }
        return new SearchResult(selectedIndex, selectedOrdinal, count);
    }

    public static SearchResult findPreviousWithStats(String text, String query, int fromIndex) {
        if (!canSearch(text, query)) {
            return SearchResult.EMPTY;
        }

        int lastStart = text.length() - query.length();
        int start = fromIndex < 0 ? lastStart : Math.min(fromIndex, lastStart);
        PatternData pattern = createPattern(query);
        MatchIterator iterator = new MatchIterator(text, pattern);
        int count = 0;
        int selectedIndex = -1;
        int selectedOrdinal = 0;
        int lastIndex = -1;
        int index;
        while ((index = iterator.next()) >= 0) {
            count++;
            lastIndex = index;
            if (index <= start) {
                selectedIndex = index;
                selectedOrdinal = count;
            }
        }
        if (selectedIndex < 0 && lastIndex >= 0) {
            selectedIndex = lastIndex;
            selectedOrdinal = count;
        }
        return new SearchResult(selectedIndex, selectedOrdinal, count);
    }

    static boolean matchesAt(String text, String query, int index) {
        return text != null && query != null
                && index >= 0
                && index + query.length() <= text.length()
                && text.regionMatches(true, index, query, 0, query.length());
    }

    private static boolean canSearch(String text, String query) {
        return text != null && query != null && !query.isBlank()
                && query.length() <= text.length();
    }

    private static PatternData createPattern(String query) {
        char[] folded = new char[query.length()];
        int[] prefix = new int[query.length()];
        for (int index = 0; index < query.length(); index++) {
            folded[index] = foldCase(query.charAt(index));
        }
        for (int index = 1, matched = 0; index < folded.length; index++) {
            while (matched > 0 && folded[index] != folded[matched]) {
                matched = prefix[matched - 1];
            }
            if (folded[index] == folded[matched]) {
                matched++;
            }
            prefix[index] = matched;
        }
        return new PatternData(folded, prefix);
    }

    private static char foldCase(char value) {
        return Character.toLowerCase(Character.toUpperCase(value));
    }

    public record SearchSummary(int matchCount, int firstMatchIndex, int selectedOrdinal) {
        private static final SearchSummary EMPTY = new SearchSummary(0, -1, 0);
    }

    public record SearchResult(int matchIndex, int ordinal, int matchCount) {
        private static final SearchResult EMPTY = new SearchResult(-1, 0, 0);
    }

    private record PatternData(char[] folded, int[] prefix) {
    }

    private static final class MatchIterator {
        private final String text;
        private final PatternData pattern;
        private int textIndex;
        private int matched;

        private MatchIterator(String text, PatternData pattern) {
            this.text = text;
            this.pattern = pattern;
        }

        private int next() {
            char[] folded = pattern.folded();
            int[] prefix = pattern.prefix();
            while (textIndex < text.length()) {
                char value = foldCase(text.charAt(textIndex));
                while (matched > 0 && value != folded[matched]) {
                    matched = prefix[matched - 1];
                }
                if (value == folded[matched]) {
                    matched++;
                }
                textIndex++;
                if (matched == folded.length) {
                    int matchIndex = textIndex - folded.length;
                    matched = prefix[matched - 1];
                    return matchIndex;
                }
            }
            return -1;
        }
    }
}
