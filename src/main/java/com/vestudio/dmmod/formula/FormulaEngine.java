package com.vestudio.dmmod.formula;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 数据驱动公式的求值引擎。
 *
 * <h2>为什么需要它</h2>
 * 乘区公式原本硬编码在 Java 里，改一个系数就要重新编译。
 * 本引擎让公式以<b>字符串</b>形式存放在数据文件（JSON）中，
 * 由运行时解析并求值，从而做到不写代码即可调整计算方式。
 *
 * <h2>支持的语法</h2>
 * <pre>
 *   数字        : 1, 0.5, 2.5
 *   变量        : base_attack_power
 *   四则运算    : + - * / %
 *   括号        : (1 + percent) * base
 *   一元符号    : -x
 *   比较        : &gt; &lt; &gt;= &lt;= == !=     （结果为 1.0 / 0.0）
 *   逻辑        : &amp;&amp; || !                 （非 0 视为真，结果为 1.0 / 0.0）
 *   三元        : 条件 ? 真值 : 假值
 *   函数        : min(a,b) max(a,b) clamp(x,min,max) abs(x) floor(x) ceil(x)
 * </pre>
 *
 * <p>例如暴击区可以写作：
 * <pre>
 *   is_critical ? crit_damage : 1
 * </pre>
 *
 * <h2>变量查找</h2>
 * 变量名交由 {@link VariableResolver} 解析。
 * 未定义的变量会抛出 {@link FormulaException}，
 * 由调用方决定是跳过该公式还是使用默认值。
 *
 * <h2>安全性</h2>
 * 本引擎只做算术求值，不涉及反射或任意代码执行。
 * 解析结果是一棵不可变的语法树，可安全地在多线程间共享复用。
 */
public final class FormulaEngine {

    private FormulaEngine() {
    }

    /**
     * 解析公式字符串为可复用的表达式。
     *
     * <p>解析结果无状态，可缓存后重复求值。
     *
     * @param expression 公式文本
     * @return 解析后的表达式
     * @throws FormulaException 语法错误时抛出
     */
    public static Expression parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new FormulaException("公式为空");
        }
        List<Token> tokens = new Lexer(expression).tokenize();
        Parser parser = new Parser(tokens, expression);
        Node node = parser.parseExpression();
        parser.expectEnd();
        return new Expression(expression, node);
    }

    /**
     * 供变量查找使用的解析器。
     */
    @FunctionalInterface
    public interface VariableResolver {
        /**
         * 解析变量名对应的数值。
         *
         * @param name 变量名
         * @return 数值
         * @throws FormulaException 变量不存在时抛出
         */
        double resolve(String name);
    }

    /**
     * 公式语法错误。
     */
    public static class FormulaException extends RuntimeException {
        /**
         * @param message 错误说明
         */
        public FormulaException(String message) {
            super(message);
        }
    }

    // ==================================================================
    // 表达式
    // ==================================================================

    /**
     * 一条已解析的公式。
     *
     * @param source 原始文本（用于日志）
     * @param root   语法树根节点
     */
    public record Expression(String source, Node root) {

        /**
         * 以求值上下文计算本公式。
         *
         * @param resolver 变量查找器
         * @return 计算结果
         */
        public double evaluate(VariableResolver resolver) {
            return root.evaluate(resolver);
        }

        @Override
        public String toString() {
            return source;
        }
    }

    /**
     * 收集公式中引用到的全部变量名。
     *
     * <p>用于加载期的白名单校验：公式只能引用已声明的变量，
     * 这样拼写错误会在加载时被拦下，而不是运行期静默取到 0。
     *
     * @param expression 已解析的公式
     * @return 变量名集合
     */
    public static java.util.Set<String> collectVariables(Expression expression) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        collect(expression.root(), out);
        return out;
    }

    /**
     * 递归收集节点中的变量名。
     *
     * @param node 节点
     * @param out  结果集合
     */
    private static void collect(Node node, java.util.Set<String> out) {
        switch (node) {
            case VariableNode v -> out.add(v.name());
            case UnaryNode u -> collect(u.operand(), out);
            case BinaryNode b -> {
                collect(b.left(), out);
                collect(b.right(), out);
            }
            case TernaryNode t -> {
                collect(t.condition(), out);
                collect(t.whenTrue(), out);
                collect(t.whenFalse(), out);
            }
            case FunctionNode f -> {
                for (Node arg : f.args()) {
                    collect(arg, out);
                }
            }
            case NumberNode ignored -> {
                // 常量不贡献变量。
            }
        }
    }

    // ==================================================================
    // 语法树节点
    // ==================================================================

    /**
     * 语法树节点。
     */
    public sealed interface Node {
        /**
         * 计算该节点的值。
         *
         * @param resolver 变量查找器
         * @return 计算结果
         */
        double evaluate(VariableResolver resolver);
    }

    /** 常量。 */
    private record NumberNode(double value) implements Node {
        @Override
        public double evaluate(VariableResolver resolver) {
            return value;
        }
    }

    /** 变量引用。 */
    private record VariableNode(String name) implements Node {
        @Override
        public double evaluate(VariableResolver resolver) {
            return resolver.resolve(name);
        }
    }

    /** 一元运算。 */
    private record UnaryNode(char op, Node operand) implements Node {
        @Override
        public double evaluate(VariableResolver resolver) {
            double v = operand.evaluate(resolver);
            return switch (op) {
                case '-' -> -v;
                // 逻辑非：非零为假、零为真，注意不能复用 truthy
                // （那是「非零转 1」，语义与取反相反）。
                case '!' -> v == 0.0D ? 1.0D : 0.0D;
                default -> throw new FormulaException("未知一元运算符: " + op);
            };
        }
    }

    /** 二元运算。 */
    private record BinaryNode(char op, Node left, Node right) implements Node {
        @Override
        public double evaluate(VariableResolver resolver) {
            // 逻辑与、或需要短路，因此不能先把两边都算出来。
            if (op == '&') {
                return truthy(left.evaluate(resolver)) != 0.0D
                        && truthy(right.evaluate(resolver)) != 0.0D ? 1.0D : 0.0D;
            }
            if (op == '|') {
                return truthy(left.evaluate(resolver)) != 0.0D
                        || truthy(right.evaluate(resolver)) != 0.0D ? 1.0D : 0.0D;
            }

            double a = left.evaluate(resolver);
            double b = right.evaluate(resolver);

            return switch (op) {
                case '+' -> a + b;
                case '-' -> a - b;
                case '*' -> a * b;
                case '/' -> b == 0.0D ? 0.0D : a / b;
                case '%' -> b == 0.0D ? 0.0D : a % b;
                case '>' -> a > b ? 1.0D : 0.0D;
                case '<' -> a < b ? 1.0D : 0.0D;
                case 'G' -> a >= b ? 1.0D : 0.0D;
                case 'L' -> a <= b ? 1.0D : 0.0D;
                case 'E' -> Math.abs(a - b) <= 1.0E-9D ? 1.0D : 0.0D;
                case 'N' -> Math.abs(a - b) > 1.0E-9D ? 1.0D : 0.0D;
                default -> throw new FormulaException("未知运算符: " + op);
            };
        }
    }

    /** 三元表达式。 */
    private record TernaryNode(Node condition, Node whenTrue, Node whenFalse) implements Node {
        @Override
        public double evaluate(VariableResolver resolver) {
            return truthy(condition.evaluate(resolver)) != 0.0D
                    ? whenTrue.evaluate(resolver)
                    : whenFalse.evaluate(resolver);
        }
    }

    /** 函数调用。 */
    private record FunctionNode(String name, List<Node> args) implements Node {
        @Override
        public double evaluate(VariableResolver resolver) {
            double[] values = new double[args.size()];
            for (int i = 0; i < values.length; i++) {
                values[i] = args.get(i).evaluate(resolver);
            }
            return switch (name) {
                case "min" -> Math.min(values[0], values[1]);
                case "max" -> Math.max(values[0], values[1]);
                case "clamp" -> Math.max(values[1], Math.min(values[2], values[0]));
                case "abs" -> Math.abs(values[0]);
                case "floor" -> Math.floor(values[0]);
                case "ceil" -> Math.ceil(values[0]);
                case "round" -> Math.round(values[0]);
                case "amplifier" -> amplifier(values[0]);
                default -> throw new FormulaException("未知函数: " + name);
            };
        }
    }

    /**
     * 增减伤换算：把「加成总和」转换为一个乘数。
     *
     * <h2>分段规则</h2>
     * <pre>
     *   Σ ≥ -0.5：      1 + Σ                   线性
     *   Σ &lt; -0.5：      0.5 / (1 + k·(|Σ| - 0.5))  对数式衰减
     * </pre>
     *
     * <h2>为什么前 50% 走线性</h2>
     * 线性段让「面板上的减免比例」与实际效果一致：
     * 减免 25% 就承伤 0.75，减免 50% 就承伤 0.5。
     * 前 50% 的减伤体验完全符合直觉。
     *
     * <h2>为什么超出 50% 改用对数曲线</h2>
     * 若继续线性，Σ 到 -1 时乘数为 0（完全免疫），再堆还会变成负数（反向治疗）。
     * 改用倒数曲线后<b>恒大于 0</b>，越堆越接近 0 但永远到不了：
     *
     * <table border="1">
     *   <caption>超出部分的效果（k=2）</caption>
     *   <tr><th>Σ</th><th>线性 1+Σ</th><th>本曲线</th></tr>
     *   <tr><td>-0.5</td><td>0.5</td><td>0.500（连续）</td></tr>
     *   <tr><td>-1.0</td><td><b>0.0</b>（免疫）</td><td>0.250</td></tr>
     *   <tr><td>-2.0</td><td><b>-1.0</b>（负伤害）</td><td>0.125</td></tr>
     * </table>
     *
     * <p>两段在 Σ=-0.5 处都取 0.5，因此曲线连续、没有跳变。
     *
     * @param sum 加成总和（正为增伤、负为减伤）
     * @return 该区的乘数，恒大于 0
     */
    public static double amplifier(double sum) {
        if (!Double.isFinite(sum)) {
            return 1.0D;
        }

        // Σ ≥ -0.5：线性段（增伤全部在此，减伤的前 50% 也在此）。
        if (sum >= -0.5D) {
            return 1.0D + sum;
        }

        // Σ < -0.5：对数式衰减段。
        // 由 0.5 / (1 + k·(超出量)) 给出，超出量为 0 时正好是 0.5，与前段连续。
        double excess = -sum - 0.5D;
        double k = com.vestudio.dmmod.Config.REDUCTION_CURVE_COEFFICIENT.get();
        if (!Double.isFinite(k) || k < 0.0D) {
            k = 2.0D;
        }
        return 0.5D / (1.0D + k * excess);
    }

    /** 把数值解释为布尔：非 0 为真。 */
    private static double truthy(double v) {
        return v != 0.0D ? 1.0D : 0.0D;
    }

    // ==================================================================
    // 词法分析
    // ==================================================================

    /**
     * 词法单元。
     *
     * @param kind  类型
     * @param text  原始文本
     * @param value 数值（仅数字有）
     */
    private record Token(Kind kind, String text, double value) {
        enum Kind { NUMBER, IDENT, OP }
    }

    private static final class Lexer {
        private final String src;
        private int pos;

        Lexer(String src) {
            this.src = src;
        }

        List<Token> tokenize() {
            List<Token> out = new ArrayList<>();
            while (pos < src.length()) {
                char c = src.charAt(pos);

                if (Character.isWhitespace(c)) {
                    pos++;
                    continue;
                }

                if (Character.isDigit(c) || (c == '.' && pos + 1 < src.length()
                        && Character.isDigit(src.charAt(pos + 1)))) {
                    out.add(readNumber());
                    continue;
                }

                if (Character.isLetter(c) || c == '_') {
                    out.add(readIdent());
                    continue;
                }

                out.add(readOperator());
            }
            return out;
        }

        private Token readNumber() {
            int start = pos;
            while (pos < src.length()
                    && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) {
                pos++;
            }
            String text = src.substring(start, pos);
            try {
                return new Token(Token.Kind.NUMBER, text, Double.parseDouble(text));
            } catch (NumberFormatException e) {
                throw new FormulaException("非法数字: " + text);
            }
        }

        private Token readIdent() {
            int start = pos;
            while (pos < src.length()
                    && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) {
                pos++;
            }
            String text = src.substring(start, pos);
            return new Token(Token.Kind.IDENT, text.toLowerCase(Locale.ROOT), 0.0D);
        }

        private Token readOperator() {
            char c = src.charAt(pos);

            // 双字符运算符优先。
            if (pos + 1 < src.length()) {
                String two = src.substring(pos, pos + 2);
                switch (two) {
                    case ">=" -> {
                        pos += 2;
                        return new Token(Token.Kind.OP, "G", 0.0D);
                    }
                    case "<=" -> {
                        pos += 2;
                        return new Token(Token.Kind.OP, "L", 0.0D);
                    }
                    case "==" -> {
                        pos += 2;
                        return new Token(Token.Kind.OP, "E", 0.0D);
                    }
                    case "!=" -> {
                        pos += 2;
                        return new Token(Token.Kind.OP, "N", 0.0D);
                    }
                    case "&&" -> {
                        pos += 2;
                        return new Token(Token.Kind.OP, "&", 0.0D);
                    }
                    case "||" -> {
                        pos += 2;
                        return new Token(Token.Kind.OP, "|", 0.0D);
                    }
                    default -> {
                        // 落到单字符处理。
                    }
                }
            }

            pos++;
            switch (c) {
                case '+', '-', '*', '/', '%', '(', ')', ',', '?', ':', '>', '<', '!' -> {
                    return new Token(Token.Kind.OP, String.valueOf(c), 0.0D);
                }
                default -> throw new FormulaException("非法字符: " + c);
            }
        }
    }

    // ==================================================================
    // 语法分析
    // ==================================================================

    private static final class Parser {
        private final List<Token> tokens;
        private final String source;
        private int pos;

        Parser(List<Token> tokens, String source) {
            this.tokens = tokens;
            this.source = source;
        }

        void expectEnd() {
            if (pos < tokens.size()) {
                throw new FormulaException("公式末尾有多余内容: " + tokens.get(pos).text());
            }
        }

        Node parseExpression() {
            return parseTernary();
        }

        private Node parseTernary() {
            Node condition = parseOr();
            if (matchOp("?")) {
                Node whenTrue = parseExpression();
                if (!matchOp(":")) {
                    throw new FormulaException("三元表达式缺少 ':'");
                }
                Node whenFalse = parseTernary();
                return new TernaryNode(condition, whenTrue, whenFalse);
            }
            return condition;
        }

        private Node parseOr() {
            Node left = parseAnd();
            while (matchOp("|")) {
                left = new BinaryNode('|', left, parseAnd());
            }
            return left;
        }

        private Node parseAnd() {
            Node left = parseComparison();
            while (matchOp("&")) {
                left = new BinaryNode('&', left, parseComparison());
            }
            return left;
        }

        private Node parseComparison() {
            Node left = parseAdditive();
            while (true) {
                if (matchOp(">")) {
                    left = new BinaryNode('>', left, parseAdditive());
                } else if (matchOp("<")) {
                    left = new BinaryNode('<', left, parseAdditive());
                } else if (matchOp("G")) {
                    left = new BinaryNode('G', left, parseAdditive());
                } else if (matchOp("L")) {
                    left = new BinaryNode('L', left, parseAdditive());
                } else if (matchOp("E")) {
                    left = new BinaryNode('E', left, parseAdditive());
                } else if (matchOp("N")) {
                    left = new BinaryNode('N', left, parseAdditive());
                } else {
                    return left;
                }
            }
        }

        private Node parseAdditive() {
            Node left = parseMultiplicative();
            while (true) {
                if (matchOp("+")) {
                    left = new BinaryNode('+', left, parseMultiplicative());
                } else if (matchOp("-")) {
                    left = new BinaryNode('-', left, parseMultiplicative());
                } else {
                    return left;
                }
            }
        }

        private Node parseMultiplicative() {
            Node left = parseUnary();
            while (true) {
                if (matchOp("*")) {
                    left = new BinaryNode('*', left, parseUnary());
                } else if (matchOp("/")) {
                    left = new BinaryNode('/', left, parseUnary());
                } else if (matchOp("%")) {
                    left = new BinaryNode('%', left, parseUnary());
                } else {
                    return left;
                }
            }
        }

        private Node parseUnary() {
            if (matchOp("-")) {
                return new UnaryNode('-', parseUnary());
            }
            if (matchOp("+")) {
                return parseUnary();
            }
            if (matchOp("!")) {
                return new UnaryNode('!', parseUnary());
            }
            return parsePrimary();
        }

        private Node parsePrimary() {
            if (pos >= tokens.size()) {
                throw new FormulaException("公式意外结束");
            }

            Token token = tokens.get(pos);

            if (token.kind() == Token.Kind.NUMBER) {
                pos++;
                return new NumberNode(token.value());
            }

            if (token.kind() == Token.Kind.IDENT) {
                pos++;
                String name = token.text();

                // 函数调用
                if (pos < tokens.size() && "(".equals(tokens.get(pos).text())) {
                    pos++;
                    List<Node> args = new ArrayList<>();
                    if (!matchOp(")")) {
                        args.add(parseExpression());
                        while (matchOp(",")) {
                            args.add(parseExpression());
                        }
                        if (!matchOp(")")) {
                            throw new FormulaException("函数调用缺少 ')'");
                        }
                    }
                    return new FunctionNode(name, args);
                }

                return new VariableNode(name);
            }

            if (matchOp("(")) {
                Node inner = parseExpression();
                if (!matchOp(")")) {
                    throw new FormulaException("缺少 ')'");
                }
                return inner;
            }

            throw new FormulaException("意外的记号: " + token.text());
        }

        private boolean matchOp(String op) {
            if (pos < tokens.size()) {
                Token t = tokens.get(pos);
                if (t.kind() == Token.Kind.OP && op.equals(t.text())) {
                    pos++;
                    return true;
                }
            }
            return false;
        }
    }
}
