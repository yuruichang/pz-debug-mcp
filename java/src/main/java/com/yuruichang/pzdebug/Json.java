package com.yuruichang.pzdebug;

import java.util.*;

/** Protocol values only; game objects must first pass through the field collector. */
public final class Json {
    private Json() {}
    public static Map<String, Object> object(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) out.put((String) pairs[i], pairs[i + 1]);
        return out;
    }
    public static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("Expected JSON object");
        @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) value;
        return result;
    }
    public static String text(Map<String, Object> args, String key, String fallback) {
        Object value = args.get(key);
        if (value == null) return fallback;
        if (!(value instanceof String text)) throw new IllegalArgumentException("Expected string: " + key);
        return text;
    }
    public static int integer(Map<String, Object> args, String key, int fallback, int min, int max) {
        Object value = args.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) ||
            n.doubleValue() != Math.rint(n.doubleValue()) || n.doubleValue() < min || n.doubleValue() > max)
            throw new IllegalArgumentException("Integer out of range: " + key);
        return n.intValue();
    }
    public static String encode(Object value) {
        StringBuilder out = new StringBuilder();
        append(out, value, 0);
        return out.toString();
    }
    private static void append(StringBuilder out, Object value, int depth) {
        if (depth > 24) throw new IllegalArgumentException("JSON nesting exceeds 24");
        if (value == null) out.append("null");
        else if (value instanceof String s) quote(out, s);
        else if (value instanceof Boolean) out.append(value);
        else if (value instanceof Number n) {
            if (!Double.isFinite(n.doubleValue())) throw new IllegalArgumentException("Non-finite number");
            out.append(n);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{'); boolean first = true;
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String)) throw new IllegalArgumentException("Non-string key");
                if (!first) out.append(','); first = false;
                quote(out, (String) entry.getKey()); out.append(':'); append(out, entry.getValue(), depth + 1);
            }
            out.append('}');
        } else if (value instanceof List<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) { if (i > 0) out.append(','); append(out, list.get(i), depth + 1); }
            out.append(']');
        } else throw new IllegalArgumentException("Not a protocol value: " + value.getClass().getName());
    }
    private static void quote(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 32 || Character.isSurrogate(c)) {
                        if (Character.isHighSurrogate(c) && i + 1 < value.length() &&
                            Character.isLowSurrogate(value.charAt(i + 1))) { out.append(c).append(value.charAt(++i)); }
                        else if (Character.isSurrogate(c)) out.append("\\ufffd");
                        else out.append(String.format("\\u%04x", (int)c));
                    } else out.append(c);
                }
            }
        }
        out.append('"');
    }
    public static Object decode(String text) {
        if (text.length() > 524288) throw new IllegalArgumentException("JSON too large");
        Parser p = new Parser(text); Object value = p.value(0); p.space();
        if (p.pos != text.length()) throw new IllegalArgumentException("Trailing JSON");
        return value;
    }
    private static final class Parser {
        final String text; int pos;
        Parser(String text) { this.text = text; }
        void space() { while (pos < text.length() && " \t\r\n".indexOf(text.charAt(pos)) >= 0) pos++; }
        boolean take(char c) { space(); if (pos < text.length() && text.charAt(pos) == c) { pos++; return true; } return false; }
        void need(char c) { if (!take(c)) throw new IllegalArgumentException("Expected " + c); }
        Object value(int depth) {
            space();
            if (depth > 24 || pos >= text.length()) throw new IllegalArgumentException("Invalid JSON nesting/input");
            char c = text.charAt(pos);
            if (c == '"') return string();
            if (take('{')) {
                Map<String, Object> map = new LinkedHashMap<>();
                if (take('}')) return map;
                do {
                    space(); String key = string(); need(':');
                    if (map.containsKey(key)) throw new IllegalArgumentException("Duplicate JSON key");
                    map.put(key, value(depth + 1));
                } while (take(','));
                need('}'); return map;
            }
            if (take('[')) {
                List<Object> list = new ArrayList<>();
                if (take(']')) return list;
                do { list.add(value(depth + 1)); } while (take(','));
                need(']'); return list;
            }
            for (String literal : List.of("true", "false", "null"))
                if (text.startsWith(literal, pos)) { pos += literal.length(); return literal.equals("null") ? null : literal.equals("true"); }
            int begin = pos;
            while (pos < text.length() && "-+0123456789.eE".indexOf(text.charAt(pos)) >= 0) pos++;
            String token = text.substring(begin, pos);
            if (!token.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) throw new IllegalArgumentException("Invalid number");
            try {
                if (!token.contains(".") && !token.contains("e") && !token.contains("E")) return Long.parseLong(token);
                double number = Double.parseDouble(token);
                if (!Double.isFinite(number)) throw new IllegalArgumentException("Non-finite number");
                return number;
            } catch (NumberFormatException e) { throw new IllegalArgumentException("Number out of range", e); }
        }
        String string() {
            need('"'); StringBuilder out = new StringBuilder(); boolean ended = false;
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '"') { ended = true; break; }
                if (c < 32) throw new IllegalArgumentException("Control character in string");
                if (c == '\\') {
                    if (pos >= text.length()) throw new IllegalArgumentException("Unfinished escape");
                    c = text.charAt(pos++);
                    switch (c) {
                        case '"', '\\', '/' -> out.append(c);
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'u' -> {
                            if (pos + 4 > text.length()) throw new IllegalArgumentException("Unfinished unicode");
                            if (!text.substring(pos, pos + 4).matches("[0-9a-fA-F]{4}")) throw new IllegalArgumentException("Invalid unicode");
                            try { out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16)); }
                            catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid unicode", e); }
                            pos += 4;
                        }
                        default -> throw new IllegalArgumentException("Invalid escape");
                    }
                } else out.append(c);
            }
            if (!ended) throw new IllegalArgumentException("Unfinished string");
            for (int i = 0; i < out.length(); i++) {
                char c = out.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (++i >= out.length() || !Character.isLowSurrogate(out.charAt(i))) throw new IllegalArgumentException("Unpaired surrogate");
                } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Unpaired surrogate");
            }
            return out.toString();
        }
    }
}
