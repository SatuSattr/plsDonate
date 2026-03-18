package click.sattr.plsDonate;

public class ExpressionEvaluator {

    /**
     * Evaluates a mathematical expression from a string using recursion.
     * Supports +, -, *, /, %, floor(), ceil(), round().
     *
     * @param str the expression to parse
     * @return the result of the calculation
     */
    public static double evaluateMath(final String str) {
        return new Object() {
            int pos = -1, ch;

            void nextChar() {
                ch = (++pos < str.length()) ? str.charAt(pos) : -1;
            }

            boolean eat(int charToEat) {
                while (ch == ' ') nextChar();
                if (ch == charToEat) {
                    nextChar();
                    return true;
                }
                return false;
            }

            double parse() {
                nextChar();
                double x = parseExpression();
                if (pos < str.length()) throw new RuntimeException("Unexpected: " + (char)ch);
                return x;
            }

            double parseExpression() {
                double x = parseTerm();
                for (;;) {
                    if      (eat('+')) x += parseTerm(); // addition
                    else if (eat('-')) x -= parseTerm(); // subtraction
                    else return x;
                }
            }

            double parseTerm() {
                double x = parseFactor();
                for (;;) {
                    if      (eat('*')) x *= parseFactor(); // multiplication
                    else if (eat('/')) x /= parseFactor(); // division
                    else if (eat('%')) x %= parseFactor(); // modulo
                    else return x;
                }
            }

            double parseFactor() {
                if (eat('+')) return parseFactor(); // unary plus
                if (eat('-')) return -parseFactor(); // unary minus

                double x;
                int startPos = this.pos;
                if (eat('(')) { // parentheses
                    x = parseExpression();
                    eat(')');
                } else if ((ch >= '0' && ch <= '9') || ch == '.') { // numbers
                    while ((ch >= '0' && ch <= '9') || ch == '.') nextChar();
                    x = Double.parseDouble(str.substring(startPos, this.pos));
                } else if (ch >= 'a' && ch <= 'z') { // functions
                    while (ch >= 'a' && ch <= 'z') nextChar();
                    String func = str.substring(startPos, this.pos);
                    x = parseFactor();
                    if (func.equals("floor")) x = Math.floor(x);
                    else if (func.equals("ceil")) x = Math.ceil(x);
                    else if (func.equals("round")) x = Math.round(x);
                    else throw new RuntimeException("Unknown function: " + func);
                } else {
                    throw new RuntimeException("Unexpected: " + (char)ch);
                }

                return x;
            }
        }.parse();
    }

    /**
     * Evaluates a conditional string (e.g., "5000 >= 1000" or "'test' contains 'es'")
     * 
     * @param condition the condition to evaluate
     * @return true if condition is met
     */
    public static boolean evaluateCondition(String condition) {
        if (condition == null || condition.trim().isEmpty()) return true; // empty conditions are considered passing

        condition = condition.trim();
        String[] operators = {"==", "!=", ">=", "<=", ">", "<", " contains ", " !contains ", " has_permission ", " !has_permission "};
        String foundOp = null;

        for (String op : operators) {
            if (condition.contains(op)) {
                foundOp = op;
                break;
            }
        }

        if (foundOp == null) {
            return false;
        }

        int idx = condition.indexOf(foundOp);
        String leftRaw = condition.substring(0, idx).trim();
        String rightRaw = condition.substring(idx + foundOp.length()).trim();

        String left = stripQuotes(leftRaw);
        String right = stripQuotes(rightRaw);

        try {
            double leftNum = Double.parseDouble(left);
            double rightNum = Double.parseDouble(right);
            double epsilon = 0.01; // Small enough for IDR currency

            return switch (foundOp.trim()) {
                case "==" -> Math.abs(leftNum - rightNum) < epsilon;
                case "!=" -> Math.abs(leftNum - rightNum) >= epsilon;
                case ">=" -> leftNum >= rightNum - epsilon;
                case "<=" -> leftNum <= rightNum + epsilon;
                case ">" -> leftNum > rightNum + epsilon;
                case "<" -> leftNum < rightNum - epsilon;
                default -> false; 
            };
        } catch (NumberFormatException e) {
            // String comparison & specific operators
            return switch (foundOp.trim()) {
                case "==" -> left.equalsIgnoreCase(right);
                case "!=" -> !left.equalsIgnoreCase(right);
                case "contains" -> left.toLowerCase().contains(right.toLowerCase());
                case "!contains" -> !left.toLowerCase().contains(right.toLowerCase());
                case "has_permission" -> {
                    org.bukkit.entity.Player p = org.bukkit.Bukkit.getPlayerExact(left);
                    yield p != null && p.hasPermission(right);
                }
                case "!has_permission" -> {
                    org.bukkit.entity.Player p = org.bukkit.Bukkit.getPlayerExact(left);
                    yield p == null || !p.hasPermission(right); // Returns true if they don't have it, or are offline
                }
                default -> false;
            };
        }
    }

    private static String stripQuotes(String s) {
        if (s == null) return s;
        s = s.trim();
        if ((s.startsWith("'") && s.endsWith("'")) || (s.startsWith("\"") && s.endsWith("\""))) {
            if (s.length() >= 2) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }
}
