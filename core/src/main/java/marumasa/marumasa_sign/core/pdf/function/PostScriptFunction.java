package marumasa.marumasa_sign.core.pdf.function;

import marumasa.marumasa_sign.core.pdf.cos.Keyword;
import marumasa.marumasa_sign.core.pdf.cos.Lexer;
import marumasa.marumasa_sign.core.pdf.cos.PdfDict;
import marumasa.marumasa_sign.core.pdf.cos.PdfStream;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Type 4 (PostScript の計算式)。{ } の中の命令を、読むときに命令の並びにしておき、評価では順に実行する。
 * 繰り返しの命令はないので必ず終わるが、スタックの深さと命令の数には上限を設ける。
 * 真偽値は、数と区別できるよう別の印を付けてスタックに置く。
 */
final class PostScriptFunction extends PdfFunction {

    private static final int MAX_STACK = 100;
    private static final int MAX_OPERATIONS = 100000;

    // 命令の番号
    private static final int PUSH = 0, IF = 1, IFELSE = 2;
    private static final String[] NAMES = {"", "if", "ifelse",
            "abs", "add", "atan", "ceiling", "cos", "cvi", "cvr", "div", "exp", "floor", "idiv", "ln", "log", "mod",
            "mul", "neg", "round", "sin", "sqrt", "sub", "truncate", "and", "bitshift", "eq", "false", "ge", "gt",
            "le", "lt", "ne", "not", "or", "true", "xor", "copy", "dup", "exch", "index", "pop", "roll"};

    // 命令の並び。if / ifelse は、直前に置いた手続き (Procedure) を使う
    private static final class Procedure {
        final List<Object> items = new ArrayList<>();
    }

    private Procedure program;
    private int outputs;

    @Override
    protected void read(PdfDict dict, PdfStream stream, int depth, int[] count) throws IOException {
        if (range == null) throw new IOException("PostScript function without Range");
        outputs = range.length / 2;
        final Lexer lexer = new Lexer(stream.decode(1 << 20));
        final Object first = lexer.next();
        if (first != Keyword.PROC_START) throw new IOException("PostScript function must start with {");
        program = parse(lexer, 0, new int[1]);
    }

    private static Procedure parse(Lexer lexer, int depth, int[] count) throws IOException {
        if (depth > 64) throw new IOException("PostScript function is nested too deeply");
        final Procedure procedure = new Procedure();
        while (true) {
            final Object token = lexer.next();
            if (token == null) return procedure;
            if (++count[0] > MAX_OPERATIONS) throw new IOException("PostScript function is too long");
            if (token == Keyword.PROC_END) return procedure;
            if (token == Keyword.PROC_START) {
                procedure.items.add(parse(lexer, depth + 1, count));
            } else if (token instanceof Number) {
                procedure.items.add(((Number) token).doubleValue());
            } else if (token instanceof Boolean) {
                procedure.items.add(token);
            } else if (token instanceof Keyword) {
                final String name = ((Keyword) token).text;
                int code = -1;
                for (int i = 1; i < NAMES.length; i++) {
                    if (NAMES[i].equals(name)) {
                        code = i;
                        break;
                    }
                }
                if (code < 0) throw new IOException("Unknown PostScript operator " + name);
                procedure.items.add(code);
            } else {
                throw new IOException("Unexpected token in PostScript function");
            }
        }
    }

    @Override
    public int outputs() {
        return outputs;
    }

    // 実行中のスタック。boolean[] は真偽値かどうかの印
    private static final class Stack {
        final double[] values = new double[MAX_STACK];
        final boolean[] bools = new boolean[MAX_STACK];
        int size;
        int steps;

        void push(double value) {
            if (size >= MAX_STACK) throw new IllegalStateException("stack overflow");
            values[size] = value;
            bools[size++] = false;
        }

        void pushBool(boolean value) {
            if (size >= MAX_STACK) throw new IllegalStateException("stack overflow");
            values[size] = value ? 1 : 0;
            bools[size++] = true;
        }

        double pop() {
            if (size == 0) throw new IllegalStateException("stack underflow");
            return values[--size];
        }

        boolean popBool() {
            if (size == 0) throw new IllegalStateException("stack underflow");
            return values[--size] != 0;
        }

        boolean topIsBool() {
            return size > 0 && bools[size - 1];
        }
    }

    @Override
    protected void compute(double[] input, double[] output) {
        final Stack stack = new Stack();
        for (double value : input) stack.push(value);
        try {
            execute(program, stack, 0);
        } catch (RuntimeException e) {
            // 実行できない式 (スタックの不足など) は、残っている値を使う
        }
        // 出力はスタックの下から順
        final int start = Math.max(0, stack.size - outputs);
        for (int i = 0; i < output.length; i++) {
            output[i] = start + i < stack.size ? stack.values[start + i] : 0;
        }
    }

    private static void execute(Procedure procedure, Stack stack, int depth) {
        if (depth > 64) throw new IllegalStateException("too deep");
        final List<Object> items = procedure.items;
        for (int i = 0; i < items.size(); i++) {
            if (++stack.steps > MAX_OPERATIONS) throw new IllegalStateException("too many steps");
            final Object item = items.get(i);
            if (item instanceof Double) {
                stack.push((Double) item);
                continue;
            }
            if (item instanceof Boolean) {
                stack.pushBool((Boolean) item);
                continue;
            }
            if (item instanceof Procedure) {
                // if / ifelse の手続き。次の命令で使う
                final Object next = i + 1 < items.size() ? items.get(i + 1) : null;
                final Object after = i + 2 < items.size() ? items.get(i + 2) : null;
                if (next instanceof Procedure && after instanceof Integer && (Integer) after == IFELSE) {
                    final boolean condition = stack.popBool();
                    execute(condition ? (Procedure) item : (Procedure) next, stack, depth + 1);
                    i += 2;
                } else if (next instanceof Integer && (Integer) next == IF) {
                    if (stack.popBool()) execute((Procedure) item, stack, depth + 1);
                    i += 1;
                }
                continue;
            }
            operate((Integer) item, stack);
        }
    }

    private static void operate(int code, Stack stack) {
        final String name = NAMES[code];
        switch (name) {
            case "abs": stack.push(Math.abs(stack.pop())); break;
            case "add": { final double b = stack.pop(); stack.push(stack.pop() + b); break; }
            case "sub": { final double b = stack.pop(); stack.push(stack.pop() - b); break; }
            case "mul": { final double b = stack.pop(); stack.push(stack.pop() * b); break; }
            case "div": { final double b = stack.pop(); final double a = stack.pop(); stack.push(b == 0 ? 0 : a / b); break; }
            case "idiv": { final long b = (long) stack.pop(); final long a = (long) stack.pop(); stack.push(b == 0 ? 0 : a / b); break; }
            case "mod": { final long b = (long) stack.pop(); final long a = (long) stack.pop(); stack.push(b == 0 ? 0 : a % b); break; }
            case "neg": stack.push(-stack.pop()); break;
            case "atan": {
                final double den = stack.pop();
                final double num = stack.pop();
                double angle = Math.toDegrees(Math.atan2(num, den));
                if (angle < 0) angle += 360;
                stack.push(angle);
                break;
            }
            case "ceiling": stack.push(Math.ceil(stack.pop())); break;
            case "floor": stack.push(Math.floor(stack.pop())); break;
            case "round": stack.push(Math.floor(stack.pop() + 0.5)); break;
            case "truncate": { final double a = stack.pop(); stack.push(a < 0 ? Math.ceil(a) : Math.floor(a)); break; }
            case "cvi": { final double a = stack.pop(); stack.push(a < 0 ? Math.ceil(a) : Math.floor(a)); break; }
            case "cvr": stack.push(stack.pop()); break;
            case "cos": stack.push(Math.cos(Math.toRadians(stack.pop()))); break;
            case "sin": stack.push(Math.sin(Math.toRadians(stack.pop()))); break;
            case "exp": { final double e = stack.pop(); stack.push(Math.pow(stack.pop(), e)); break; }
            case "ln": stack.push(Math.log(stack.pop())); break;
            case "log": stack.push(Math.log10(stack.pop())); break;
            case "sqrt": stack.push(Math.sqrt(Math.max(0, stack.pop()))); break;
            case "true": stack.pushBool(true); break;
            case "false": stack.pushBool(false); break;
            case "not":
                if (stack.topIsBool()) stack.pushBool(!stack.popBool());
                else stack.push(~(long) stack.pop());
                break;
            case "and": case "or": case "xor": {
                final boolean bools = stack.topIsBool();
                final double b = stack.pop();
                final double a = stack.pop();
                if (bools) {
                    final boolean x = a != 0, y = b != 0;
                    stack.pushBool(name.equals("and") ? x && y : name.equals("or") ? x || y : x ^ y);
                } else {
                    final long x = (long) a, y = (long) b;
                    stack.push(name.equals("and") ? x & y : name.equals("or") ? x | y : x ^ y);
                }
                break;
            }
            case "bitshift": {
                final int shift = (int) stack.pop();
                final long value = (long) stack.pop();
                stack.push(shift >= 0 ? (shift > 63 ? 0 : value << shift) : (-shift > 63 ? 0 : value >> -shift));
                break;
            }
            case "eq": { final double b = stack.pop(); stack.pushBool(stack.pop() == b); break; }
            case "ne": { final double b = stack.pop(); stack.pushBool(stack.pop() != b); break; }
            case "ge": { final double b = stack.pop(); stack.pushBool(stack.pop() >= b); break; }
            case "gt": { final double b = stack.pop(); stack.pushBool(stack.pop() > b); break; }
            case "le": { final double b = stack.pop(); stack.pushBool(stack.pop() <= b); break; }
            case "lt": { final double b = stack.pop(); stack.pushBool(stack.pop() < b); break; }
            case "pop": stack.pop(); break;
            case "dup": {
                if (stack.size == 0) throw new IllegalStateException("stack underflow");
                final boolean bool = stack.bools[stack.size - 1];
                final double value = stack.values[stack.size - 1];
                if (bool) stack.pushBool(value != 0); else stack.push(value);
                break;
            }
            case "exch": {
                if (stack.size < 2) throw new IllegalStateException("stack underflow");
                swap(stack, stack.size - 1, stack.size - 2);
                break;
            }
            case "copy": {
                final int n = (int) stack.pop();
                if (n < 0 || n > stack.size || stack.size + n > MAX_STACK) throw new IllegalStateException("bad copy");
                final int base = stack.size - n;
                for (int i = 0; i < n; i++) {
                    stack.values[stack.size] = stack.values[base + i];
                    stack.bools[stack.size++] = stack.bools[base + i];
                }
                break;
            }
            case "index": {
                final int n = (int) stack.pop();
                if (n < 0 || n >= stack.size) throw new IllegalStateException("bad index");
                final int from = stack.size - 1 - n;
                if (stack.bools[from]) stack.pushBool(stack.values[from] != 0); else stack.push(stack.values[from]);
                break;
            }
            case "roll": {
                int j = (int) stack.pop();
                final int n = (int) stack.pop();
                if (n < 0 || n > stack.size) throw new IllegalStateException("bad roll");
                if (n == 0) break;
                j %= n;
                if (j < 0) j += n;
                final int base = stack.size - n;
                final double[] values = new double[n];
                final boolean[] bools = new boolean[n];
                for (int i = 0; i < n; i++) {
                    values[(i + j) % n] = stack.values[base + i];
                    bools[(i + j) % n] = stack.bools[base + i];
                }
                System.arraycopy(values, 0, stack.values, base, n);
                System.arraycopy(bools, 0, stack.bools, base, n);
                break;
            }
            default:
                throw new IllegalStateException("unexpected " + name);
        }
    }

    private static void swap(Stack stack, int a, int b) {
        final double value = stack.values[a];
        stack.values[a] = stack.values[b];
        stack.values[b] = value;
        final boolean bool = stack.bools[a];
        stack.bools[a] = stack.bools[b];
        stack.bools[b] = bool;
    }
}
