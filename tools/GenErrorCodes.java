import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates the {@code AST__} status constants of {@code AstException} from the C library's
 * {@code ast_err.msg}, with the values Starlink's MESSGEN gives them.
 *
 * <p>Usage: {@code java tools/GenErrorCodes.java reference/starlink-ast/ast_err.msg
 * src/main/java/uk/ac/starlink/ast/AstException.java}. Only the text between the {@code BEGIN
 * GENERATED ERROR CODES} and {@code END GENERATED ERROR CODES} comments is rewritten.
 *
 * <p>The MESSGEN formula is {@code ((facility | 0x800) << 16) + ((number | 0x1000) << 3) +
 * severity}, where the facility comes from {@code .FACILITY}, the message number starts at
 * {@code .BASE} and increases by one per message, and the severity from {@code .SEVERITY} is 0
 * (WARNING), 1 (SUCCESS), 2 (ERROR), 3 (INFORMATIONAL) or 4 (SEVERE).
 */
public final class GenErrorCodes {

    private static final String BEGIN = "    // BEGIN GENERATED ERROR CODES";
    private static final String END = "    // END GENERATED ERROR CODES";

    private record Code(String name, int value, String description) {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: java tools/GenErrorCodes.java <ast_err.msg> <AstException.java>");
            System.exit(2);
        }
        List<Code> codes = parse(Paths.get(args[0]));
        Path target = Paths.get(args[1]);
        String src = Files.readString(target);
        int b = src.indexOf(BEGIN);
        int e = src.indexOf(END);
        if (b < 0 || e < b) {
            throw new IllegalStateException("No generated block in " + target);
        }
        int bodyStart = src.indexOf('\n', b) + 1;
        Files.writeString(target, src.substring(0, bodyStart) + block(codes) + src.substring(e));
        System.out.println("Wrote " + codes.size() + " codes to " + target);
    }

    private static List<Code> parse(Path msg) throws IOException {
        List<Code> out = new ArrayList<>();
        long facility = -1;
        long severity = -1;
        long number = -1;
        for (String raw : Files.readAllLines(msg, StandardCharsets.UTF_8)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("!") || line.startsWith(".TITLE") || line.startsWith(".END")) {
                continue;
            }
            if (line.startsWith(".FACILITY")) {
                String rest = line.substring(".FACILITY".length()).strip();
                facility = Long.parseLong(rest.substring(rest.indexOf(',') + 1, rest.indexOf('/')));
            } else if (line.startsWith(".SEVERITY")) {
                severity = switch (line.substring(".SEVERITY".length()).strip()) {
                    case "WARNING" -> 0;
                    case "SUCCESS" -> 1;
                    case "ERROR" -> 2;
                    case "INFORMATIONAL" -> 3;
                    case "SEVERE" -> 4;
                    default -> throw new IllegalArgumentException("Unknown severity: " + line);
                };
            } else if (line.startsWith(".BASE")) {
                number = Long.parseLong(line.substring(".BASE".length()).strip());
            } else {
                int lt = line.indexOf('<');
                int gt = line.lastIndexOf('>');
                if (lt < 0 || gt < lt || facility < 0 || severity < 0 || number < 0) {
                    throw new IllegalArgumentException("Unexpected line: " + line);
                }
                long value = ((facility | 0x800) << 16) + ((number | 0x1000) << 3) + severity;
                out.add(new Code("AST__" + line.substring(0, lt).strip(), (int) value, line.substring(lt + 1, gt)));
                number++;
            }
        }
        return out;
    }

    private static String block(List<Code> codes) {
        StringBuilder sb = new StringBuilder();
        for (Code c : codes) {
            sb.append("\n    /** Status constant for the error \"")
                    .append(javadoc(c.description()))
                    .append("\". */\n    public static final int ")
                    .append(c.name())
                    .append(" = ")
                    .append(c.value())
                    .append(";\n");
        }
        sb.append("\n    private static final int[] CODES = {\n");
        for (Code c : codes) {
            sb.append("        ").append(c.name()).append(",\n");
        }
        sb.append("    };\n\n    private static final String[] NAMES = {\n");
        for (Code c : codes) {
            sb.append("        \"").append(c.name()).append("\",\n");
        }
        sb.append("    };\n\n    private static final String[] DESCRIPTIONS = {\n");
        for (Code c : codes) {
            sb.append("        \"")
                    .append(c.description().replace("\\", "\\\\").replace("\"", "\\\""))
                    .append("\",\n");
        }
        sb.append("    };\n");
        return sb.toString();
    }

    private static String javadoc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("*/", "*&#47;");
    }
}
