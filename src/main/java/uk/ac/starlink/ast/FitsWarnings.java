package uk.ac.starlink.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The warnings a FitsChan read or write raises, and the conditions C's {@code Warn} reports
 * (fitschan.c): every warning carries the lower case name of its condition, and is stored, as
 * {@code ASTWARN} cards and in the Channel's warnings, only if the FitsChan's Warnings attribute
 * names that condition.
 *
 * <p>With Strict in force a warning the Warnings attribute names is not added: {@link #push}
 * throws it as the error C's {@code astAddWarning} reports, so the work that raised it stops there,
 * as C's does.
 *
 * <p>Ported from Rust {@code fitschan/warning.rs}.
 */
final class FitsWarnings {

    /**
     * Every condition the Warnings attribute accepts, in lower case, with a space before and after
     * each name (C's {@code ALLWARNINGS}).
     */
    static final String ALL_WARNINGS = " distortion noequinox noradesys nomjd-obs nolonpole nolatpole tnx zpx badcel"
            + " noctype badlat badmat badval badctype badpv badkeyname badkeyvalue badalt ";

    /** The default of the Warnings attribute. */
    static final String DEFAULT_WARNINGS = "BadKeyName BadKeyValue Tnx Zpx BadPV BadCel BadMat BadCTYPE";

    /** A warning and the condition that raised it. */
    static final class Warning {
        /** The lower case condition name, one of those in {@link #ALL_WARNINGS}. */
        final String condition;

        /** The text of the warning. */
        final String text;

        /** The method an error for it names, where C names its own rather than the list's; or null. */
        final String method;

        Warning(String condition, String text) {
            this(condition, text, null);
        }

        private Warning(String condition, String text, String method) {
            this.condition = condition;
            this.text = text;
            this.method = method;
        }

        /** This warning, with an error for it naming {@code method}. */
        Warning namedBy(String method) {
            return new Warning(condition, text, method);
        }
    }

    /** The warnings raised, in order. */
    private final List<Warning> items = new ArrayList<>();

    /** The Warnings attribute that makes a warning an error, or null without Strict. */
    private final String strictConditions;

    /** The method an error is reported from, with Strict. */
    private final String strictMethod;

    /** An empty list, to which every warning is added. */
    FitsWarnings() {
        this(null, null);
    }

    private FitsWarnings(String conditions, String method) {
        this.strictConditions = conditions;
        this.strictMethod = method;
    }

    /**
     * An empty list for a FitsChan with Strict in force, whose Warnings attribute is {@code
     * conditions}, raised by {@code method}.
     */
    static FitsWarnings strict(String conditions, String method) {
        return new FitsWarnings(conditions, method);
    }

    /** An empty list that treats warnings as this one does. */
    FitsWarnings child() {
        return new FitsWarnings(strictConditions, strictMethod);
    }

    /**
     * Throws the error {@code warning} is with Strict in force and its condition reported.
     *
     * @throws AstException with status {@code AST__BADIN} in that case
     */
    void check(Warning warning) {
        if (strictConditions != null && !warning.text.isEmpty() && reports(strictConditions, warning.condition)) {
            throw strictError(warning.method != null ? warning.method : strictMethod, warning.text);
        }
    }

    /**
     * Adds {@code warning}.
     *
     * @throws AstException with status {@code AST__BADIN} if Strict is in force and its condition
     *     is reported; nothing is then added
     */
    void push(Warning warning) {
        check(warning);
        items.add(warning);
    }

    /** Adds the warnings of {@code other}, which were checked as they were added to it. */
    void append(FitsWarnings other) {
        items.addAll(other.items);
    }

    /** The warnings, in the order raised. */
    List<Warning> list() {
        return Collections.unmodifiableList(items);
    }

    /** The number of warnings. */
    int size() {
        return items.size();
    }

    /** The error C's {@code astAddWarning} reports for a warning with Strict set. */
    static AstException strictError(String method, String text) {
        return Ast.err(AstException.AST__BADIN, method + "(FitsChan): " + text);
    }

    /**
     * C's {@code GoodWarns}: checks that {@code value} is a list of known conditions, separated by
     * white space and in any case.
     *
     * @throws AstException with status {@code AST__ATTIN} naming the first unknown condition
     */
    static void goodWarns(String value) {
        int i = 0;
        int n = value.length();
        while (i < n) {
            while (i < n && CScan.isCSpace(value.charAt(i))) {
                i++;
            }
            int start = i;
            while (i < n && !CScan.isCSpace(value.charAt(i))) {
                i++;
            }
            if (i > start) {
                StringBuilder word = new StringBuilder(i - start);
                for (int k = start; k < i; k++) {
                    word.append(CScan.toLower(value.charAt(k)));
                }
                if (!ALL_WARNINGS.contains(" " + word + " ")) {
                    throw Ast.err(
                            AstException.AST__ATTIN,
                            "astSetWarnings(fitschan): Unknown condition '" + word
                                    + "' specified when setting the Warnings attribute.");
                }
            }
        }
    }

    /**
     * Whether the Warnings value {@code list} names {@code condition}: C's {@code FullForm( list,
     * condition, 0 ) >= 0}, which splits the list at spaces only and compares each word, without
     * trailing blanks, ignoring case.
     */
    static boolean reports(String list, String condition) {
        int i = 0;
        int n = list.length();
        while (i < n) {
            int sp = list.indexOf(' ', i);
            int end = sp < 0 ? n : sp;
            if (end > i) {
                String option = list.substring(i, end);
                String word = option.substring(0, FitsCard.chrLen(option));
                if (word.equalsIgnoreCase(condition)) {
                    return true;
                }
            }
            i = end + 1;
        }
        return false;
    }
}
