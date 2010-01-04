package com.cforcoding.text;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author William Shields
 */
public class TextUtils {
    public static boolean find(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find();
    }

    public static boolean matches(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.matches();
    }

    public static void match(String pattern, String text, MatchCallback callback) {
        match(Pattern.compile(pattern), text, callback);
    }

    public static void match(String pattern, int flags, String text, MatchCallback callback) {
        match(Pattern.compile(pattern, flags), text, callback);
    }

    public static void match(Pattern pattern, String text, MatchCallback callback) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            callback.match(m.toMatchResult());
        }
    }

    public static String replace(String pattern, String text, ReplaceCallback callback) {
        return replace(Pattern.compile(pattern), text, callback);
    }

    public static String replace(String pattern, int flags, String text, ReplaceCallback callback) {
        return replace(Pattern.compile(pattern, flags), text, callback);
    }

    public static String replace(Pattern pattern, String text, ReplaceCallback callback) {
        Matcher m = pattern.matcher(text);
        boolean result = m.find();
        if (result) {
            StringBuffer sb = new StringBuffer();
            do {
                m.appendReplacement(sb, Matcher.quoteReplacement(callback.match(m.toMatchResult())));
                result = m.find();
            } while (result);
            m.appendTail(sb);
            return sb.toString();
        }
        return text;
    }

    public static String replace(String pattern, String text, String replacement) {
        return replace(Pattern.compile(pattern), text, replacement);
    }

    public static String replace(String pattern, int flags, String text, String replacement) {
        return replace(Pattern.compile(pattern, flags), text, replacement);
    }

    public static String replace(Pattern pattern, String text, String replacement) {
        Matcher m = pattern.matcher(text);
        boolean result = m.find();
        if (result) {
            StringBuffer sb = new StringBuffer();
            do {
                m.appendReplacement(sb, replacement);
                result = m.find();
            } while (result);
            m.appendTail(sb);
            return sb.toString();
        }
        return text;
    }

    public static <T> List<T> tokenize(String pattern, String text, TokenizeCallback<T> callback) {
        return tokenize(Pattern.compile(pattern), text, callback);
    }

    public static <T> List<T> tokenize(String pattern, int flags, String text, TokenizeCallback<T> callback) {
        return tokenize(Pattern.compile(pattern, flags), text, callback);
    }

    public static <T> List<T> tokenize(Pattern pattern, String text, TokenizeCallback<T> callback) {
        List<T> ret = new ArrayList<T>();
        Matcher m = pattern.matcher(text);
        boolean result = m.find();
        int pos = 0;
        if (result) {
            do {
                if (pos < m.start()) {
                    ret.add(callback.text(text.substring(pos, m.start())));
                }
                ret.add(callback.match(m.toMatchResult()));
                pos = m.end();
                result = m.find();
            } while (result);
            if (pos < text.length()) {
                ret.add(callback.text(text.substring(pos)));
            }
        } else {
            ret.add(callback.text(text));
        }
        return ret;
    }

    public static String repeat(String s, int count) {
        StringBuilder sb = new StringBuilder(s.length() * count);
        while (count-- > 0) {
            sb.append(s);
        }
        return sb.toString();
    }

    public static boolean empty(String s) {
        return s == null || s.length() == 0;
    }

    public static String join(String delim, String... args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(delim);
            }
            sb.append(args[i]);
        }
        return sb.toString();
    }
}
