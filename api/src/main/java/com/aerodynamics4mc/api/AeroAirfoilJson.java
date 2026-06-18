package com.aerodynamics4mc.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class AeroAirfoilJson {
    private AeroAirfoilJson() {
    }

    public static String write(AeroAirfoilDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        StringBuilder builder = new StringBuilder(2048);
        builder.append("{\n");
        appendField(builder, 1, "format", definition.format(), true);
        appendField(builder, 1, "id", definition.id().toString(), true);
        appendField(builder, 1, "display_name", definition.displayName(), true);
        builder.append(indent(1)).append("\"profile\": {\n");
        appendField(builder, 2, "id", definition.profile().id().toString(), true);
        appendField(builder, 2, "kind", kindName(definition.profile().kind()), true);
        appendField(builder, 2, "max_camber_ratio", definition.profile().maxCamberRatio(), true);
        appendField(builder, 2, "max_camber_position_ratio", definition.profile().maxCamberPositionRatio(), true);
        appendField(builder, 2, "thickness_ratio", definition.profile().thicknessRatio(), false);
        builder.append(indent(1)).append("},\n");
        builder.append(indent(1)).append("\"coordinates\": [");
        if (!definition.coordinates().isEmpty()) {
            builder.append('\n');
            for (int i = 0; i < definition.coordinates().size(); i++) {
                AeroAirfoilCoordinate coordinate = definition.coordinates().get(i);
                builder.append(indent(2)).append("{\"x\": ")
                    .append(number(coordinate.x()))
                    .append(", \"y\": ")
                    .append(number(coordinate.y()))
                    .append('}');
                if (i + 1 < definition.coordinates().size()) {
                    builder.append(',');
                }
                builder.append('\n');
            }
            builder.append(indent(1));
        }
        builder.append("],\n");
        appendField(builder, 1, "source", definition.source(), true);
        appendField(builder, 1, "notes", definition.notes(), false);
        builder.append("}\n");
        return builder.toString();
    }

    public static AeroAirfoilDefinition read(String json) {
        Objects.requireNonNull(json, "json");
        Object parsed = new Parser(json).parse();
        Map<String, Object> root = asObject(parsed, "root");
        String format = string(root, "format", AeroAirfoilDefinition.FORMAT_V1);
        A4mcId id = A4mcId.parse(requiredString(root, "id"));
        String displayName = requiredString(root, "display_name");
        Map<String, Object> profileObject = asObject(root.get("profile"), "profile");
        AeroAirfoilProfile profile = readProfile(id, profileObject);
        List<AeroAirfoilCoordinate> coordinates = readCoordinates(root.get("coordinates"));
        return new AeroAirfoilDefinition(
            format,
            id,
            displayName,
            profile,
            coordinates,
            string(root, "source", ""),
            string(root, "notes", "")
        );
    }

    private static AeroAirfoilProfile readProfile(A4mcId definitionId, Map<String, Object> profileObject) {
        A4mcId profileId = A4mcId.parse(string(profileObject, "id", definitionId.toString()));
        AeroAirfoilProfile.Kind kind = kind(requiredString(profileObject, "kind"));
        return new AeroAirfoilProfile(
            profileId,
            kind,
            requiredNumber(profileObject, "max_camber_ratio"),
            requiredNumber(profileObject, "max_camber_position_ratio"),
            requiredNumber(profileObject, "thickness_ratio")
        );
    }

    private static List<AeroAirfoilCoordinate> readCoordinates(Object value) {
        if (value == null) {
            return List.of();
        }
        List<Object> array = asArray(value, "coordinates");
        if (array.isEmpty()) {
            return List.of();
        }
        List<AeroAirfoilCoordinate> coordinates = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            Map<String, Object> coordinate = asObject(array.get(i), "coordinates[" + i + "]");
            coordinates.add(new AeroAirfoilCoordinate(
                requiredNumber(coordinate, "x"),
                requiredNumber(coordinate, "y")
            ));
        }
        return List.copyOf(coordinates);
    }

    private static void appendField(StringBuilder builder, int level, String name, String value, boolean comma) {
        builder.append(indent(level))
            .append('"').append(escape(name)).append("\": ")
            .append('"').append(escape(value)).append('"');
        if (comma) {
            builder.append(',');
        }
        builder.append('\n');
    }

    private static void appendField(StringBuilder builder, int level, String name, double value, boolean comma) {
        builder.append(indent(level))
            .append('"').append(escape(name)).append("\": ")
            .append(number(value));
        if (comma) {
            builder.append(',');
        }
        builder.append('\n');
    }

    private static String indent(int level) {
        return "  ".repeat(level);
    }

    private static String escape(String value) {
        String safeValue = value == null ? "" : value;
        StringBuilder builder = new StringBuilder(safeValue.length() + 16);
        for (int i = 0; i < safeValue.length(); i++) {
            char c = safeValue.charAt(i);
            switch (c) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (c < 0x20) {
                        builder.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        return builder.toString();
    }

    private static String number(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("JSON number must be finite");
        }
        return Double.toString(value);
    }

    private static String kindName(AeroAirfoilProfile.Kind kind) {
        return kind.name().toLowerCase(Locale.ROOT);
    }

    private static AeroAirfoilProfile.Kind kind(String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return AeroAirfoilProfile.Kind.valueOf(normalized);
    }

    private static String requiredString(Map<String, Object> object, String name) {
        Object value = object.get(name);
        if (value instanceof String string && !string.trim().isEmpty()) {
            return string;
        }
        throw new IllegalArgumentException(name + " must be a non-empty string");
    }

    private static String string(Map<String, Object> object, String name, String fallback) {
        Object value = object.get(name);
        if (value == null) {
            return fallback;
        }
        if (value instanceof String string) {
            return string;
        }
        throw new IllegalArgumentException(name + " must be a string");
    }

    private static double requiredNumber(Map<String, Object> object, String name) {
        Object value = object.get(name);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        throw new IllegalArgumentException(name + " must be a number");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object value, String name) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException(name + " must be a JSON object");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asArray(Object value, String name) {
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        throw new IllegalArgumentException(name + " must be a JSON array");
    }

    private static final class Parser {
        private final String input;
        private int index;

        private Parser(String input) {
            this.input = input;
        }

        private Object parse() {
            Object value = parseValue();
            skipWhitespace();
            if (index != input.length()) {
                throw error("unexpected trailing data");
            }
            return value;
        }

        private Object parseValue() {
            skipWhitespace();
            if (index >= input.length()) {
                throw error("unexpected end of input");
            }
            char c = input.charAt(index);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> {
                    if (c == '-' || Character.isDigit(c)) {
                        yield parseNumber();
                    }
                    throw error("unexpected character '" + c + "'");
                }
            };
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> object = new LinkedHashMap<>();
            skipWhitespace();
            if (peek('}')) {
                index++;
                return object;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                object.put(key, parseValue());
                skipWhitespace();
                if (peek('}')) {
                    index++;
                    return object;
                }
                expect(',');
            }
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> array = new ArrayList<>();
            skipWhitespace();
            if (peek(']')) {
                index++;
                return array;
            }
            while (true) {
                array.add(parseValue());
                skipWhitespace();
                if (peek(']')) {
                    index++;
                    return array;
                }
                expect(',');
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (index < input.length()) {
                char c = input.charAt(index++);
                if (c == '"') {
                    return builder.toString();
                }
                if (c != '\\') {
                    if (c < 0x20) {
                        throw error("unescaped control character in string");
                    }
                    builder.append(c);
                    continue;
                }
                if (index >= input.length()) {
                    throw error("unterminated escape sequence");
                }
                char escaped = input.charAt(index++);
                switch (escaped) {
                    case '"' -> builder.append('"');
                    case '\\' -> builder.append('\\');
                    case '/' -> builder.append('/');
                    case 'b' -> builder.append('\b');
                    case 'f' -> builder.append('\f');
                    case 'n' -> builder.append('\n');
                    case 'r' -> builder.append('\r');
                    case 't' -> builder.append('\t');
                    case 'u' -> builder.append(parseUnicodeEscape());
                    default -> throw error("invalid escape sequence \\" + escaped);
                }
            }
            throw error("unterminated string");
        }

        private char parseUnicodeEscape() {
            if (index + 4 > input.length()) {
                throw error("incomplete unicode escape");
            }
            int value = 0;
            for (int i = 0; i < 4; i++) {
                char c = input.charAt(index++);
                int digit = Character.digit(c, 16);
                if (digit < 0) {
                    throw error("invalid unicode escape");
                }
                value = value * 16 + digit;
            }
            return (char) value;
        }

        private Double parseNumber() {
            int start = index;
            if (peek('-')) {
                index++;
            }
            readDigits();
            if (peek('.')) {
                index++;
                readDigits();
            }
            if (peek('e') || peek('E')) {
                index++;
                if (peek('+') || peek('-')) {
                    index++;
                }
                readDigits();
            }
            try {
                return Double.parseDouble(input.substring(start, index));
            } catch (NumberFormatException e) {
                throw error("invalid number");
            }
        }

        private Object parseLiteral(String literal, Object value) {
            if (!input.startsWith(literal, index)) {
                throw error("invalid literal");
            }
            index += literal.length();
            return value;
        }

        private void readDigits() {
            int start = index;
            while (index < input.length() && Character.isDigit(input.charAt(index))) {
                index++;
            }
            if (index == start) {
                throw error("expected digit");
            }
        }

        private boolean peek(char expected) {
            return index < input.length() && input.charAt(index) == expected;
        }

        private void expect(char expected) {
            skipWhitespace();
            if (!peek(expected)) {
                throw error("expected '" + expected + "'");
            }
            index++;
        }

        private void skipWhitespace() {
            while (index < input.length()) {
                char c = input.charAt(index);
                if (c != ' ' && c != '\n' && c != '\r' && c != '\t') {
                    return;
                }
                index++;
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at byte " + index);
        }
    }
}
