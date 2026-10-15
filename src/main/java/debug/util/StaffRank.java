/*
 * Rank detection for staff members, simplified port of Anubis StaffMatcher (GPL-3.0, credit 4ldenz).
 * Looks at the TAB display name / scoreboard team prefix+suffix for rank words (Owner, Admin, Mod, ...).
 */
package debug.util;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class StaffRank {
    private StaffRank() {}

    public record Match(String label, int seniority) {}

    private static final String GENERIC = "Staff";
    private static final int TEAM_RANK_MAX = 5;
    private static final Pattern TEAM_RANK = Pattern.compile("^(\\d{2})-");
    private static final int[] MARKERS = {0x2605, 0x2606, 0x2726, 0x2727, 0x272A, 0x272F, 0x2730, 0x2B50};

    // order = seniority (lower index = more senior)
    private static final String[][] WORDS = {
        {"owner", "Owner"}, {"coowner", "Co-Owner"}, {"manager", "Manager"}, {"admin", "Admin"},
        {"administrator", "Admin"}, {"developer", "Dev"}, {"dev", "Dev"}, {"sradmin", "Sr. Admin"},
        {"srmod", "Sr. Mod"}, {"seniormod", "Sr. Mod"}, {"moderator", "Mod"}, {"mod", "Mod"},
        {"trialmod", "Trial Mod"}, {"srhelper", "Sr. Helper"}, {"seniorhelper", "Sr. Helper"},
        {"helper", "Helper"}, {"trialhelper", "Trial Helper"}, {"support", "Support"}, {"staff", "Staff"}
    };
    private static final Map<String, Integer> WORD_INDEX = new HashMap<>();
    private static final Map<Integer, Character> SMALL_CAPS = smallCaps();

    static {
        for (int i = 0; i < WORDS.length; i++) WORD_INDEX.put(WORDS[i][0], i);
    }

    /** Always returns a match: staff names come from a known list, so the worst case is the generic "Staff". */
    public static Match match(String name, String teamName, String... texts) {
        StringBuilder combined = new StringBuilder();
        for (String t : texts) {
            if (t != null && !t.isEmpty()) combined.append(t).append(' ');
        }
        if (teamName != null && !teamName.isEmpty()) combined.append(teamName).append(' ');
        String around = withoutName(stripLegacyCodes(combined.toString()), name == null ? "" : name);

        int best = bestRank(around);
        if (best >= 0) return new Match(WORDS[best][1], best);

        int teamRank = teamRank(teamName);
        if (teamRank >= 0 && teamRank <= TEAM_RANK_MAX) {
            return new Match(teamRank < 10 ? "0" + teamRank : Integer.toString(teamRank), WORDS.length + teamRank);
        }
        return new Match(GENERIC, WORDS.length + 100);
    }

    private static int teamRank(String teamName) {
        if (teamName == null || teamName.length() < 3) return -1;
        Matcher m = TEAM_RANK.matcher(teamName);
        return m.lookingAt() ? Integer.parseInt(m.group(1)) : -1;
    }

    private static int bestRank(String text) {
        List<String> tokens = tokens(text);
        int best = Integer.MAX_VALUE;
        for (int i = 0; i < tokens.size(); ) {
            int matchedSpan = 0;
            for (int span = Math.min(3, tokens.size() - i); span >= 1; span--) {
                Integer index = WORD_INDEX.get(String.join("", tokens.subList(i, i + span)));
                if (index == null) continue;
                best = Math.min(best, index);
                matchedSpan = span;
                break;
            }
            i += Math.max(1, matchedSpan);
        }
        return best == Integer.MAX_VALUE ? -1 : best;
    }

    /** True if the text contains one of the staff star markers (kept for callers that want a marker check). */
    public static boolean hasMarker(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            for (int m : MARKERS) if (m == cp) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    private static List<String> tokens(String text) {
        String folded = fold(text);
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < folded.length(); i++) {
            char c = folded.charAt(i);
            if (c >= 'a' && c <= 'z') {
                cur.append(c);
                continue;
            }
            if (cur.length() > 0) {
                out.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static String stripLegacyCodes(String text) {
        if (text.indexOf('\u00a7') < 0) return text;
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u00a7') {
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static String fold(String text) {
        String decomposed = Normalizer.normalize(text.replace('\u01eb', 'q'), Normalizer.Form.NFKD);
        StringBuilder out = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); ) {
            int cp = decomposed.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.getType(cp) == Character.NON_SPACING_MARK) continue;
            Character small = SMALL_CAPS.get(cp);
            if (small != null) {
                out.append(small.charValue());
                continue;
            }
            int lower = Character.toLowerCase(cp);
            out.append(lower >= 'a' && lower <= 'z' ? (char) lower : ' ');
        }
        return out.toString();
    }

    private static String withoutName(String text, String name) {
        if (name.isEmpty()) return text;
        String lowerText = text.toLowerCase(Locale.ROOT);
        String lowerName = name.toLowerCase(Locale.ROOT);
        int at = lowerText.indexOf(lowerName);
        if (at < 0) return text;
        StringBuilder out = new StringBuilder(text.length());
        int from = 0;
        while (at >= 0) {
            out.append(text, from, at).append(' ');
            from = at + lowerName.length();
            at = lowerText.indexOf(lowerName, from);
        }
        return out.append(text, from, text.length()).toString();
    }

    private static Map<Integer, Character> smallCaps() {
        int[] codepoints = {7424, 665, 7428, 7429, 7431, 42800, 610, 668, 618, 7434, 7435, 671, 7437, 628, 7439,
            7448, 42927, 640, 42801, 7451, 7452, 7456, 7457, 655, 7458};
        String letters = "abcdefghijklmnopqrstuvwyz";
        Map<Integer, Character> map = new HashMap<>();
        for (int i = 0; i < codepoints.length; i++) map.put(codepoints[i], letters.charAt(i));
        return Map.copyOf(map);
    }
}
