package com.aerodynamics4mc.api;

import java.util.Objects;

public record A4mcId(String namespace, String path) {
    public static final String DEFAULT_NAMESPACE = "minecraft";

    public A4mcId {
        namespace = Objects.requireNonNull(namespace, "namespace");
        path = Objects.requireNonNull(path, "path");
        if (!isValidNamespace(namespace)) {
            throw new IllegalArgumentException("Invalid namespace: " + namespace);
        }
        if (!isValidPath(path)) {
            throw new IllegalArgumentException("Invalid path: " + path);
        }
    }

    public static A4mcId of(String namespace, String path) {
        return new A4mcId(namespace, path);
    }

    public static A4mcId parse(String value) {
        Objects.requireNonNull(value, "value");
        int separator = value.indexOf(':');
        if (separator >= 0) {
            return new A4mcId(value.substring(0, separator), value.substring(separator + 1));
        }
        return new A4mcId(DEFAULT_NAMESPACE, value);
    }

    public static boolean isValidNamespace(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!isNamespaceChar(c)) {
                return false;
            }
        }
        return true;
    }

    public static boolean isValidPath(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!isPathChar(c)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isNamespaceChar(char c) {
        return c >= 'a' && c <= 'z'
            || c >= '0' && c <= '9'
            || c == '_'
            || c == '-'
            || c == '.';
    }

    private static boolean isPathChar(char c) {
        return isNamespaceChar(c) || c == '/';
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }
}
