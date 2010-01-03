package com.cforcoding.text;

import java.util.regex.MatchResult;

/**
 * @author William Shields
 */
public interface TokenCallback<T> {
    T match(MatchResult match);
}
