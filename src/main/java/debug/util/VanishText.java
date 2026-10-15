/*
 * Text helpers + sound/particle heuristics.
 * Ported from Anubis AntiVanishText / AntiVanishHeuristics (GPL-3.0, credit 4ldenz).
 */
package debug.util;

import java.text.Normalizer;
import java.util.Locale;

public final class VanishText {
    private VanishText() {}

    // ---- heuristics ----

    public static boolean suspiciousSound(String id) {
        String p = path(id);
        return p.contains("chest.open") || p.contains("chest.close") || p.contains("chest.locked")
            || p.contains("barrel.open") || p.contains("barrel.close")
            || p.contains("shulker_box.open") || p.contains("shulker_box.close")
            || p.contains("door.open") || p.contains("door.close")
            || p.contains("fence_gate.open") || p.contains("fence_gate.close")
            || p.contains("button.click") || p.contains("lever.click");
    }

    public static boolean suspiciousParticle(String id) {
        String p = path(id);
        return p.contains("crit") || p.contains("enchanted_hit") || p.contains("damage_indicator")
            || p.contains("smoke") || p.equals("block");
    }

    public static String path(String id) {
        if (id == null) return "unknown";
        int split = id.indexOf(':');
        return (split >= 0 && split + 1 < id.length() ? id.substring(split + 1) : id).toLowerCase(Locale.ROOT);
    }

    // ---- text ----

    // Lowercases, strips colour codes/marks, folds look-alike letters; word breaks become single spaces.
    public static String normalize(String text) {
        if (text == null || text.isBlank()) return "";
        String decomposed = Normalizer.normalize(stripLegacyCodes(text), Normalizer.Form.NFKD).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(decomposed.length());
        boolean spaced = true;
        for (int off = 0; off < decomposed.length(); ) {
            int cp = decomposed.codePointAt(off);
            off += Character.charCount(cp);
            if (isMark(cp) || isFormatCode(cp)) continue;
            int folded = foldConfusable(cp);
            if ((folded >= 'a' && folded <= 'z') || (folded >= '0' && folded <= '9')) {
                out.appendCodePoint(folded);
                spaced = false;
            } else if (!spaced) {
                out.append(' ');
                spaced = true;
            }
        }
        int len = out.length();
        if (len > 0 && out.charAt(len - 1) == ' ') out.setLength(len - 1);
        return out.toString();
    }

    public static boolean containsPlayerName(String displayedName, String playerName) {
        String rawDisplay = stripLegacyCodes(displayedName == null ? "" : displayedName).toLowerCase(Locale.ROOT);
        String rawPlayer = stripLegacyCodes(playerName == null ? "" : playerName).trim().toLowerCase(Locale.ROOT);
        if (rawPlayer.matches("[a-z0-9_]{1,16}")) {
            int from = 0;
            while (from <= rawDisplay.length() - rawPlayer.length()) {
                int match = rawDisplay.indexOf(rawPlayer, from);
                if (match < 0) return false;
                int end = match + rawPlayer.length();
                boolean left = match == 0 || !isUsernameChar(rawDisplay.charAt(match - 1));
                boolean right = end == rawDisplay.length() || !isUsernameChar(rawDisplay.charAt(end));
                if (left && right) return true;
                from = match + 1;
            }
            return false;
        }
        String displayed = normalize(displayedName);
        String player = normalize(playerName);
        if (displayed.isBlank() || player.isBlank()) return false;
        return displayed.equals(player) || (" " + displayed + " ").contains(" " + player + " ");
    }

    public static boolean looksLikeLeaveMessage(String message, String playerName) {
        if (!containsPlayerName(message, playerName)) return false;
        String text = " " + normalize(message) + " ";
        String player = normalize(playerName);
        if (player.isBlank()) return false;
        String prefix = " " + player + " ";
        return text.contains(prefix + "left the game ") || text.contains(prefix + "left ")
            || text.contains(prefix + "has left ") || text.contains(prefix + "quit ")
            || text.contains(prefix + "has quit ") || text.contains(prefix + "disconnected ")
            || text.contains(prefix + "logged out ");
    }

    public static boolean isPlausiblePlayerName(String name) {
        if (name == null) return false;
        String t = name.trim();
        return t.length() >= 3 && t.length() <= 16 && t.indexOf(' ') < 0;
    }

    public static boolean isUsername(String name) {
        if (name == null) return false;
        int len = name.length();
        if (len < 2 || len > 32) return false;
        boolean hasAlnum = false;
        for (int i = 0; i < len; i++) {
            char c = name.charAt(i);
            boolean alnum = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (alnum) { hasAlnum = true; continue; }
            if (c == '_' || c == '.' || c == ' ') continue;
            return false;
        }
        return hasAlnum;
    }

    private static boolean isUsernameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
    }

    private static boolean isMark(int cp) {
        int t = Character.getType(cp);
        return t == Character.NON_SPACING_MARK || t == Character.COMBINING_SPACING_MARK || t == Character.ENCLOSING_MARK;
    }

    private static boolean isFormatCode(int cp) {
        return cp == 0xA7 || cp == 0x200B || cp == 0x200C || cp == 0x200D || cp == 0xFEFF;
    }

    private static String stripLegacyCodes(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u00a7' && i + 1 < text.length()
                && "0123456789abcdefklmnorx".indexOf(Character.toLowerCase(text.charAt(i + 1))) >= 0) {
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static int foldConfusable(int cp) {
        return switch (cp) {
            case 593, 945, 1072, 7424 -> 'a';
            case 665, 946, 1074 -> 'b';
            case 1010, 1089, 7428 -> 'c';
            case 1281, 7429 -> 'd';
            case 949, 1077, 7431 -> 'e';
            case 42800 -> 'f';
            case 609, 610 -> 'g';
            case 668, 1085 -> 'h';
            case 618, 953, 1110 -> 'i';
            case 1112, 7434 -> 'j';
            case 954, 1082, 7435 -> 'k';
            case 671 -> 'l';
            case 1084, 7437 -> 'm';
            case 628, 1400 -> 'n';
            case 959, 1086, 7439 -> 'o';
            case 961, 1088, 7448 -> 'p';
            case 1307 -> 'q';
            case 640 -> 'r';
            case 1109, 42801 -> 's';
            case 964, 1090, 7451 -> 't';
            case 965, 7452 -> 'u';
            case 957, 7456 -> 'v';
            case 1121, 7457 -> 'w';
            case 1093 -> 'x';
            case 655, 1091 -> 'y';
            case 7458, 7459 -> 'z';
            default -> cp;
        };
    }
}
