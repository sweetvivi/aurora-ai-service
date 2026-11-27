package com.hub.backend.infra.newapi;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a New API {@code billing_expr} into per-variable unit prices.
 *
 * <p>New API publishes tier expressions for models it bills dynamically, for example
 * {@code len <= 272000 ? tier("standard", p * 2.5 + c * 15 + cr * 0.25) : tier("long_context", ...)}.
 * The endpoint never publishes the evaluated tier table, but it does not need to: New API divides
 * the expression result by one million before applying the quota ratio, so every coefficient in the
 * expression is already USD per million tokens ({@code p} = prompt tokens, {@code c} = completion
 * tokens, {@code cr} = cache-read tokens, {@code cc} = cache-write tokens, {@code img} = image input
 * tokens). {@code fixed(x)} is a flat USD amount per request.</p>
 *
 * <p>The extractor evaluates the parsed expression once per variable with that variable set to one
 * million tokens and everything else at zero, so the coefficient falls out of the difference without
 * ever inventing a number from {@code model_ratio}. Context-length conditions are turned into price
 * tiers by probing both sides of every {@code len} comparison.</p>
 *
 * <p>Expressions that depend on something a catalogue cannot answer (wall-clock windows such as
 * {@code weekday(...)}, or request rules) are rejected instead of guessed, which keeps those models
 * unpriced exactly as before.</p>
 */
final class BillingExprPrices {

    private static final double MILLION_AS_DOUBLE = 1_000_000d;
    private static final int SCALE = 10;

    /** {@code len <= 272000}, {@code len > 1000}, … */
    private static final Pattern LEN_LEFT =
            Pattern.compile("\\blen\\s*(?:<=|>=|==|<|>)\\s*(\\d+(?:\\.\\d+)?)");
    /** {@code 272000 >= len}, … */
    private static final Pattern LEN_RIGHT =
            Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:<=|>=|==|<|>)\\s*\\blen\\b");

    private static final Set<String> VARIABLES = Set.of(
            "p", "c", "len", "cr", "cc", "cc1h", "img", "img_cr", "img_o", "ai", "ao",
            "image_count");

    private BillingExprPrices() {
    }

    /** One context-length tier of the expression, prices in USD per million tokens. */
    record Tier(Long minTokens, Long maxTokens, Coefficients coefficients) {
    }

    /** Coefficients of one tier; {@code null} means the expression does not price that component. */
    record Coefficients(BigDecimal input,
                        BigDecimal output,
                        BigDecimal cacheWrite,
                        BigDecimal cacheWrite1h,
                        BigDecimal cacheRead,
                        BigDecimal imageInput,
                        BigDecimal imageOutput,
                        BigDecimal perRequest) {
    }

    /**
     * @return one entry per context-length tier, or an empty list when the expression cannot be read
     *     without guessing (time windows, request rules, unknown variables)
     */
    static List<Tier> tiers(String expression) {
        if (expression == null || expression.isBlank()) {
            return List.of();
        }
        Node root;
        Parser parser = new Parser(expression);
        try {
            root = parser.parse();
        } catch (UnsupportedExpression exception) {
            return List.of();
        }

        boolean perRequestBilling = parser.sawFixed();
        List<Long> probes = probePoints(expression);
        List<Tier> tiers = new ArrayList<>(probes.size());
        for (int i = 0; i < probes.size(); i++) {
            long start = probes.get(i);
            Long end = i + 1 < probes.size() ? probes.get(i + 1) - 1 : null;
            tiers.add(new Tier(start, end, measure(root, start, perRequestBilling)));
        }
        return mergeAdjacent(tiers);
    }

    private static Coefficients measure(Node root, double len, boolean perRequestBilling) {
        double constant = root.eval(new Env(len, null));
        return new Coefficients(
                coefficient(root, len, "p"),
                coefficient(root, len, "c"),
                coefficient(root, len, "cc"),
                coefficient(root, len, "cc1h"),
                coefficient(root, len, "cr"),
                coefficient(root, len, "img"),
                coefficient(root, len, "img_o"),
                // Only fixed(...) turns a constant into a per-request price; a stray constant in a
                // token expression is bookkeeping, not a charge per call.
                perRequestBilling ? usd(constant) : null);
    }

    private static BigDecimal coefficient(Node root, double len, String variable) {
        double withVariable = root.eval(new Env(len, variable));
        double withoutVariable = root.eval(new Env(len, null));
        return usd(withVariable - withoutVariable);
    }

    /** The expression result is USD per million tokens scaled by one million again. */
    private static BigDecimal usd(double scaledValue) {
        if (Math.abs(scaledValue) < 1e-9) {
            return null;
        }
        return BigDecimal.valueOf(scaledValue / MILLION_AS_DOUBLE)
                .setScale(SCALE, RoundingMode.HALF_UP)
                .stripTrailingZeros();
    }

    /**
     * Every {@code len} threshold splits the expression into two branches, and {@code <=} and {@code <}
     * split it on different sides of the number, so both the threshold and threshold + 1 are probed.
     */
    private static List<Long> probePoints(String expression) {
        TreeSet<Long> points = new TreeSet<>();
        points.add(0L);
        collectThresholds(expression, LEN_LEFT, points);
        collectThresholds(expression, LEN_RIGHT, points);
        List<Long> probes = new ArrayList<>(points.size() * 2);
        for (Long point : points) {
            probes.add(point);
            probes.add(point + 1);
        }
        return probes.stream().distinct().sorted().toList();
    }

    private static void collectThresholds(String expression, Pattern pattern, TreeSet<Long> target) {
        Matcher matcher = pattern.matcher(expression);
        while (matcher.find()) {
            String value = matcher.group(1);
            try {
                target.add((long) Double.parseDouble(value));
            } catch (NumberFormatException ignored) {
                // A malformed threshold leaves the expression without tiers, not with a wrong one.
            }
        }
    }

    private static List<Tier> mergeAdjacent(List<Tier> tiers) {
        List<Tier> merged = new ArrayList<>(tiers.size());
        for (Tier tier : tiers) {
            if (!merged.isEmpty()) {
                Tier previous = merged.get(merged.size() - 1);
                if (sameCoefficients(previous.coefficients(), tier.coefficients())) {
                    merged.set(merged.size() - 1,
                            new Tier(previous.minTokens(), tier.maxTokens(), previous.coefficients()));
                    continue;
                }
            }
            merged.add(tier);
        }
        return List.copyOf(merged);
    }

    private static boolean sameCoefficients(Coefficients left, Coefficients right) {
        return same(left.input(), right.input())
                && same(left.output(), right.output())
                && same(left.cacheWrite(), right.cacheWrite())
                && same(left.cacheWrite1h(), right.cacheWrite1h())
                && same(left.cacheRead(), right.cacheRead())
                && same(left.imageInput(), right.imageInput())
                && same(left.imageOutput(), right.imageOutput())
                && same(left.perRequest(), right.perRequest());
    }

    private static boolean same(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.compareTo(right) == 0;
    }

    /** Variables the evaluator understands; {@code len} drives the tier condition only. */
    private record Env(double len, String unitVariable) {

        double value(String variable) {
            return switch (variable) {
                case "len" -> len;
                // New API bills a single image when the request does not say otherwise.
                case "image_count" -> 1d;
                default -> variable.equals(unitVariable) ? MILLION_AS_DOUBLE : 0d;
            };
        }
    }

    @FunctionalInterface
    private interface Node {

        double eval(Env env);
    }

    private static final class UnsupportedExpression extends RuntimeException {

        UnsupportedExpression(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * Recursive-descent reader for the expression language New API accepts: arithmetic, comparisons,
     * {@code &&}/{@code ||}/{@code !}, the {@code condition ? a : b} ternary, and the two functions a
     * price can be read from ({@code tier(name, value)} and {@code fixed(amount)}).
     */
    private static final class Parser {

        private final String source;
        private int index;
        private boolean sawFixed;

        Parser(String source) {
            this.source = source;
        }

        /** Whether the expression prices a flat amount per call instead of tokens. */
        boolean sawFixed() {
            return sawFixed;
        }

        Node parse() {
            Node node = parseTernary();
            skipWhitespace();
            if (index < source.length()) {
                throw unsupported("unexpected trailing input");
            }
            return node;
        }

        private Node parseTernary() {
            Node condition = parseOr();
            if (!consume('?')) {
                return condition;
            }
            Node whenTrue = parseTernary();
            expect(':');
            Node whenFalse = parseTernary();
            return env -> condition.eval(env) != 0d ? whenTrue.eval(env) : whenFalse.eval(env);
        }

        private Node parseOr() {
            Node left = parseAnd();
            while (consume("||")) {
                Node right = parseAnd();
                Node previous = left;
                left = env -> truth(isTrue(previous.eval(env)) || isTrue(right.eval(env)));
            }
            return left;
        }

        private Node parseAnd() {
            Node left = parseComparison();
            while (consume("&&")) {
                Node right = parseComparison();
                Node previous = left;
                left = env -> truth(isTrue(previous.eval(env)) && isTrue(right.eval(env)));
            }
            return left;
        }

        private Node parseComparison() {
            Node left = parseAdditive();
            while (true) {
                String operator = comparisonOperator();
                if (operator == null) {
                    return left;
                }
                Node right = parseAdditive();
                Node previous = left;
                left = switch (operator) {
                    case "<" -> env -> truth(previous.eval(env) < right.eval(env));
                    case "<=" -> env -> truth(previous.eval(env) <= right.eval(env));
                    case ">" -> env -> truth(previous.eval(env) > right.eval(env));
                    case ">=" -> env -> truth(previous.eval(env) >= right.eval(env));
                    case "==" -> env -> truth(previous.eval(env) == right.eval(env));
                    default -> env -> truth(previous.eval(env) != right.eval(env));
                };
            }
        }

        private Node parseAdditive() {
            Node left = parseMultiplicative();
            while (true) {
                if (consume('+')) {
                    Node right = parseMultiplicative();
                    Node previous = left;
                    left = env -> previous.eval(env) + right.eval(env);
                } else if (consume('-')) {
                    Node right = parseMultiplicative();
                    Node previous = left;
                    left = env -> previous.eval(env) - right.eval(env);
                } else {
                    return left;
                }
            }
        }

        private Node parseMultiplicative() {
            Node left = parseUnary();
            while (true) {
                if (consume('*')) {
                    Node right = parseUnary();
                    Node previous = left;
                    left = env -> previous.eval(env) * right.eval(env);
                } else if (consume('/')) {
                    Node right = parseUnary();
                    Node previous = left;
                    left = env -> previous.eval(env) / right.eval(env);
                } else {
                    return left;
                }
            }
        }

        private Node parseUnary() {
            if (consume('-')) {
                Node operand = parseUnary();
                return env -> -operand.eval(env);
            }
            if (consume('!')) {
                Node operand = parseUnary();
                return env -> truth(!isTrue(operand.eval(env)));
            }
            return parsePrimary();
        }

        private Node parsePrimary() {
            skipWhitespace();
            if (index >= source.length()) {
                throw unsupported("unexpected end of expression");
            }
            char current = source.charAt(index);
            if (current == '(') {
                index++;
                Node inner = parseTernary();
                expect(')');
                return inner;
            }
            if (current == '"') {
                throw unsupported("string literal outside a function call");
            }
            if (Character.isDigit(current) || current == '.') {
                return number();
            }
            if (Character.isLetter(current) || current == '_') {
                return identifier();
            }
            throw unsupported("unexpected character '" + current + "'");
        }

        private Node number() {
            int start = index;
            while (index < source.length()
                    && (Character.isDigit(source.charAt(index)) || source.charAt(index) == '.')) {
                index++;
            }
            double value;
            try {
                value = Double.parseDouble(source.substring(start, index));
            } catch (NumberFormatException exception) {
                throw unsupported("malformed number");
            }
            return env -> value;
        }

        private Node identifier() {
            int start = index;
            while (index < source.length()
                    && (Character.isLetterOrDigit(source.charAt(index))
                    || source.charAt(index) == '_')) {
                index++;
            }
            String name = source.substring(start, index);
            skipWhitespace();
            if (index < source.length() && source.charAt(index) == '(') {
                return call(name);
            }
            if (!VARIABLES.contains(name)) {
                throw unsupported("unknown variable " + name);
            }
            return env -> env.value(name);
        }

        private Node call(String name) {
            expect('(');
            if ("tier".equals(name)) {
                skipWhitespace();
                if (index < source.length() && source.charAt(index) == '"') {
                    consumeString();
                    expect(',');
                }
                Node value = parseTernary();
                expect(')');
                return value;
            }
            if ("fixed".equals(name)) {
                sawFixed = true;
                Node amount = parseTernary();
                expect(')');
                return env -> amount.eval(env) * MILLION_AS_DOUBLE;
            }
            throw unsupported("unsupported function " + name);
        }

        private void consumeString() {
            index++;
            while (index < source.length()) {
                char current = source.charAt(index++);
                if (current == '\\' && index < source.length()) {
                    index++;
                } else if (current == '"') {
                    return;
                }
            }
            throw unsupported("unterminated string literal");
        }

        private String comparisonOperator() {
            skipWhitespace();
            if (index + 1 < source.length()) {
                String pair = source.substring(index, index + 2);
                if ("<=".equals(pair) || ">=".equals(pair)
                        || "==".equals(pair) || "!=".equals(pair)) {
                    index += 2;
                    return pair;
                }
            }
            if (index < source.length()) {
                char current = source.charAt(index);
                if (current == '<' || current == '>') {
                    index++;
                    return String.valueOf(current);
                }
            }
            return null;
        }

        private boolean consume(String token) {
            skipWhitespace();
            if (source.startsWith(token, index)) {
                index += token.length();
                return true;
            }
            return false;
        }

        private boolean consume(char token) {
            skipWhitespace();
            if (index < source.length() && source.charAt(index) == token) {
                index++;
                return true;
            }
            return false;
        }

        private void expect(char token) {
            if (!consume(token)) {
                throw unsupported("expected '" + token + "'");
            }
        }

        private void skipWhitespace() {
            while (index < source.length() && Character.isWhitespace(source.charAt(index))) {
                index++;
            }
        }

        private static double truth(boolean value) {
            return value ? 1d : 0d;
        }

        private static boolean isTrue(double value) {
            return value != 0d;
        }

        private static UnsupportedExpression unsupported(String message) {
            return new UnsupportedExpression(message);
        }
    }
}
