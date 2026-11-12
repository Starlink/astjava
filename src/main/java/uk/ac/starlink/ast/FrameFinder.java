package uk.ac.starlink.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * The searches that align one Frame with another: C's {@code astMatch} wrapper, which retries a
 * template cast into the target's class, and the bodies of frame.c's {@code FindFrame} and {@code
 * ConvertX}, which the Frame methods {@link Frame#findFrame} and {@link Frame#convertX} call.
 * FrameSet overrides those methods with frameset.c's searches through its Frames. The matching
 * of one Frame with another belongs to the classes, in their overrides of {@link Frame#match},
 * {@link Frame#subFrame} and {@link Frame#overlay}.
 *
 * <p>Ported from Rust {@code frames/find_frame.rs} and C {@code frame.c}.
 */
final class FrameFinder {

    private FrameFinder() {}

    /**
     * What a successful {@link Frame#match} gives: C's four returned values of {@code astMatch}.
     */
    // MODERN-JAVA(16): record
    static final class MatchResult {

        /**
         * For each axis of the result Frame, the template axis it is associated with, numbered from
         * 0, or -1 for none.
         */
        final int[] templateAxes;

        /**
         * For each axis of the result Frame, the target axis it is associated with, numbered from 0,
         * or -1 for none.
         */
        final int[] targetAxes;

        /** The Mapping from the target's coordinates to the result Frame's. */
        final Mapping map;

        /** The result Frame: the target's attributes with those set in the template overlaid. */
        final Frame result;

        MatchResult(int[] templateAxes, int[] targetAxes, Mapping map, Frame result) {
            this.templateAxes = templateAxes;
            this.targetAxes = targetAxes;
            this.map = map;
            this.result = result;
        }
    }

    /**
     * Matches a template against a target (C's {@code astMatch}, frame.c {@code astMatch_}): the
     * template's own {@link Frame#match} first; if that finds nothing and {@code matchSub} is
     * set, the template is cast into the target's class, when that class is one of its
     * ancestors, and the cast is matched instead. So a template of a specialised class, which
     * cannot probe a target of a plainer class, can still be aligned with one that shares its
     * Domain. The cast takes the template's Domain when the target's Domain is set and the
     * template's is not blank, since the ancestral class may give a different default.
     *
     * @param template the template
     * @param target the target
     * @param matchSub whether the cast may be tried
     * @return the match, or null if there is none
     */
    static MatchResult match(Frame template, Frame target, boolean matchSub) {
        MatchResult m = template.match(target, matchSub);
        if (m == null && matchSub) {
            Frame superThis = template.cast(target);
            if (superThis != null) {
                if (target.testDomain()) {
                    String dom = template.getDomain();
                    if (chrLen(dom) > 0) {
                        superThis.setDomain(dom);
                    }
                }
                m = superThis.match(target, matchSub);
            }
        }
        return m;
    }

    /** The length of a string without trailing white space (C's {@code astChrLen}). */
    private static int chrLen(String s) {
        int n = s.length();
        while (n > 0 && CScan.isCSpace(s.charAt(n - 1))) {
            n--;
        }
        return n;
    }

    /**
     * The fields of a comma-separated domain list, as C's loops over {@code strchr( domain, ',' )}
     * find them: an empty list or two adjacent commas give an empty field.
     */
    static List<String> domainFields(String domainlist) {
        List<String> fields = new ArrayList<>();
        int start = 0;
        while (true) {
            int comma = domainlist.indexOf(',', start);
            if (comma < 0) {
                fields.add(domainlist.substring(start));
                return fields;
            }
            fields.add(domainlist.substring(start, comma));
            start = comma + 1;
        }
    }

    /**
     * A Domain with its white space removed and its letters in upper case (C's {@code
     * CleanDomain}, frame.c).
     */
    static String cleanDomain(String domain) {
        StringBuilder sb = new StringBuilder(domain.length());
        for (int i = 0; i < domain.length(); i++) {
            char c = domain.charAt(i);
            if (!CScan.isCSpace(c)) {
                sb.append(CScan.toUpper(c));
            }
        }
        return sb.toString();
    }

    /**
     * The search of a target that is not a FrameSet for a coordinate system matching a template
     * (frame.c {@code FindFrame}). The template is matched against the target without the subclass
     * retry, and the result is accepted if its Domain is in the domain list or the list has an
     * empty field. Matching the list ignores case and white space in the list but not in the
     * Domain, which is stored in that form.
     *
     * @param target the target
     * @param template the template
     * @param domainlist the Domains to accept, separated by commas
     * @return a FrameSet whose base Frame is the target and whose current Frame is the result of
     *     the match, related by the simplified Mapping, or null if there is no match
     */
    static FrameSet findFrame(Frame target, Frame template, String domainlist) {
        String list = "," + cleanDomain(domainlist) + ",";
        MatchResult m = match(template, target, false);
        if (m == null) {
            return null;
        }
        String domain = "," + m.result.getDomain() + ",";
        if (!list.contains(domain) && !list.contains(",,")) {
            return null;
        }
        Mapping map = m.map.simplify();
        FrameSet result = new FrameSet(target);
        result.addFrame(FrameSet.AST__BASE, map, m.result);
        return result;
    }

    /**
     * The conversion between two Frames neither of which is a FrameSet (frame.c {@code ConvertX}).
     * For each field of the domain list in turn, until a conversion is found, each Frame in turn
     * (first {@code to}, then {@code from}) is copied, with PreserveAxes cleared and the field, if
     * not blank, as its Domain, to make a first guess at a common coordinate system; matched
     * against the other Frame it gives a better guess, which is then matched against the Frame it
     * was copied from. The Mapping from {@code from} through the common system to {@code to} is
     * simplified and scored, 2 for a forward transformation and 1 for an inverse, and the best is
     * kept; a score of 3 ends the search.
     *
     * @param to the Frame to convert to
     * @param from the Frame to convert from
     * @param domainlist the Domains through which to convert, separated by commas
     * @return a FrameSet whose base Frame is {@code from} and whose current Frame is {@code to},
     *     or null if there is no conversion
     */
    static FrameSet convertX(Frame to, Frame from, String domainlist) {
        Mapping resultMap = null;
        boolean match = false;
        for (String field : domainFields(domainlist)) {
            if (match) {
                break;
            }
            String domain = cleanDomain(field);
            int bestScore = -1;
            boolean perfect = false;
            for (int icom = 0; !perfect && icom <= 1; icom++) {
                Frame common0 = (Frame) (icom == 1 ? from : to).copy();
                common0.setPreserveAxes(false);
                if (!domain.isEmpty()) {
                    common0.setDomain(domain);
                }
                Frame frame1 = icom == 1 ? to : from;
                MatchResult m1 = match(common0, frame1, true);
                if (m1 == null) {
                    continue;
                }
                Frame common1 = m1.result;
                Frame frame2 = icom == 1 ? from : to;
                common1.setPreserveAxes(false);
                MatchResult m2 = match(common1, frame2, true);
                if (m2 == null) {
                    continue;
                }
                Mapping fromMap = icom == 1 ? m2.map : m1.map;
                Mapping toMap = icom == 1 ? m1.map : m2.map;
                toMap.invert();
                Mapping map = CmpMap.transientCmpMap(fromMap, toMap, true).simplify();
                int score = (map.getTranForward() ? 2 : 0) + (map.getTranInverse() ? 1 : 0);
                if (score > bestScore) {
                    match = true;
                    bestScore = score;
                    perfect = bestScore >= 3;
                    resultMap = map;
                }
            }
        }
        if (resultMap == null) {
            return null;
        }
        FrameSet result = new FrameSet(from);
        result.addFrame(FrameSet.AST__BASE, resultMap, to);
        return result;
    }
}
