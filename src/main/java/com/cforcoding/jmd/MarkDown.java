package com.cforcoding.jmd;

import com.cforcoding.text.ReplaceCallback;
import com.cforcoding.text.TokenizeCallback;

import java.util.*;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

import static com.cforcoding.text.TextUtils.*;

/**
 * <p>Port of <a href="http://code.google.com/p/markdownsharp/">MarkdownSharp</a> for Java 5+</p>
 *
 * @author William Shields
 */
public class MarkDown {
    /**
     * <p>enter ">" here for HTML output</p>
     * <p>enter " />" here for XHTML output</p>
     */
    //public static final String EMPTY_ELEMENT_SUFFIX = ">";
    public static final String EMPTY_ELEMENT_SUFFIX = " />";

    /**
     * Tabs are automatically converted to spaces as part of the transform
     * this variable determines how "wide" those tabs become in spaces
     */
    public static final int TAB_WIDTH = 4;

    /**
     * maximum nested bracket depth supported by the transform
     */
    public static final int NESTED_BRACKET_DEPTH = 6;

    /**
     * when true, email addresses will be auto-linked if present
     */
    public static final boolean LINK_EMAILS = true;

    /**
     * when true, bold and italic require non-word characters on either side
     */
    public static final boolean STRICT_BOLD_ITALIC = false;

    /**
     * when this is true, RETURN becomes a literal newline.
     * Beware: this is a major deviation from the Markdown spec!
     */
    public static final boolean AUTO_NEWLINES = false;

    /**
     * when true, (most) bare plain URLs are auto-hyperlinked
     * WARNING: this is a significant deviation from the markdown spec
     */
    private static final boolean AUTO_HYPERLINK = false;

    /**
     * when true, problematic URL characters like [, ], (, and so forth will be encoded
     * WARNING: this is a significant deviation from the markdown spec
     */
    private static final boolean ENCODE_PROBLEM_URL_CHARACTERS = false;

    private static final String MARKER_UL_PATTERN = "[*+-]";
    private static final Pattern MARKER_UL = Pattern.compile(MARKER_UL_PATTERN);

    private static final String MARKER_OL_PATTERN = "\\d+[.]";

    /**
     * Reusable pattern to match balanced [brackets]. See Friedl's
     * "Mastering Regular Expressions", 2nd Ed., pp. 328-331.
     * in other words [this] and [this[also]] and [this[also[too]]]
     * up to _nestDepth
     */
    private static final String NESTED_BRACKETS_PATTERN = repeat("" +
            "(?>" +         // Atomic matching
            "[^\\[\\]]+" +  // Anything other than brackets
            "|" +
            "\\[", NESTED_BRACKET_DEPTH) + repeat("" +
            "\\]" +
            ")*", NESTED_BRACKET_DEPTH);

    /**
     * Reusable pattern to match balanced (parens). See Friedl's
     * "Mastering Regular Expressions", 2nd Ed., pp. 328-331.
     * in other words (this) and (this(also)) and (this(also(too)))
     * up to _nestDepth
     */
    private static final String NESTED_PARENS_PATTERN = repeat("" +
            "(?>" +         // Atomic matching
            "[^()\\s]+" +   // Anything other than brackets
            "|" +
            "\\(", NESTED_BRACKET_DEPTH) + repeat("" +
            "\\)" +
            ")*", NESTED_BRACKET_DEPTH);

    private static final String MARKER_ANY_PATTERN = String.format("(?:%s|%s)", MARKER_OL_PATTERN, MARKER_UL_PATTERN);

    private static final String ESCAPE_CHARACTERS = "\\`*_{}[]()>#+-.!";
    private static final Map<String, String> ESCAPE_TABLE;
    private static final Map<String, String> BACKSLASH_ESCAPE_TABLE;

    private static final String BOLD_PATTERN;
    private static final String BOLD_REPLACE;
    private static final String ITALIC_PATTERN;
    private static final String ITALIC_REPLACE;

    private static final Pattern LINE_BREAK;
    private static final String LINE_BREAK_ELEMENT = "<br" + EMPTY_ELEMENT_SUFFIX;

    private Map<String, String> urls;
    private Map<String, String> titles;
    private Map<String, String> htmlBlocks;

    private int listLevel = 0;

    static {
        Map<String, String> escape = new HashMap<String, String>();
        Map<String, String> backslashEscape = new HashMap<String, String>();
        for (int i = 0; i < ESCAPE_CHARACTERS.length(); i++) {
            char c = ESCAPE_CHARACTERS.charAt(i);
            String ch = Character.toString(c);
            String code = Integer.toString(ch.hashCode());
            escape.put(ch, code);
            backslashEscape.put("\\" + ch, code);
        }
        ESCAPE_TABLE = Collections.unmodifiableMap(escape);
        BACKSLASH_ESCAPE_TABLE = Collections.unmodifiableMap(backslashEscape);

        if (STRICT_BOLD_ITALIC) {
            BOLD_PATTERN = "([\\W_]|^)(\\*\\*|__)(?=\\S)([^\\r]*?\\S[\\*_]*)\\2([\\W_]|$)";
            BOLD_REPLACE = "$1<strong>$3</strong>$4";
            ITALIC_PATTERN = "([\\W_]|^)(\\*|_)(?=\\S)([^\\r\\*_]*?\\S)\\2([\\W_]|$)";
            ITALIC_REPLACE = "$1<em>$3</em>$4";
        } else {
            BOLD_PATTERN = "(\\*\\*|__)(?=\\S)(.+?[*_]*)(?<=\\S)\\1";
            BOLD_REPLACE = "<strong>$2</strong>";
            ITALIC_PATTERN = "(\\*|_)(?=\\S)(.+?)(?<=\\S)\\1";
            ITALIC_REPLACE = "<em>$2</em>";
        }
        LINE_BREAK = Pattern.compile(AUTO_NEWLINES ? "\n" : " {2,}\n");
    }

    /**
     * Main function. The order in which other subs are called here is
     * essential. Link and image substitutions need to happen before
     * EscapeSpecialChars(), so that any *'s or _'s in the &lt;a&gt;
     * and &lt;img&gt; tags get encoded.
     *
     * @param text Markdown text
     * @return Markdown converted to (X)HTML (as configured) as a String
     */
    public String transform(String text) {
        // The MarkdownSharp version keeps the maps (dictionaries) around and just
        // clears them on repeated use. This can be bad in Java as repeated use may
        // promote them to PermGen space, which isn't usually what you want so it's
        // usually better just to create new instances each time. Plus there will be
        // no stale reference issues to these.
        urls = new HashMap<String, String>();
        titles = new HashMap<String, String>();
        htmlBlocks = new HashMap<String, String>();

        // Standardize line endings
        text = text.replace("\r\n", "\n");
        text = text.replace("\r", "\n");

        // Make sure $text ends with a couple of newlines:
        text += "\n\n";

        // Convert all tabs to spaces.
        text = detab(text);

        // Strip any lines consisting only of spaces and tabs.
        // This makes subsequent regexen easier to write, because we can
        // match consecutive blank lines with /\n+/ instead of something
        // contorted like /[ \t]*\n+/ .
        // In the MarkdownSharp version this is a multi-line regex for
        // some reason but it can't traverse lines as the only matching
        // characters are spaces or tabs.
        text = replace(BLANK_LINE, text, "");

        // Turn block-level HTML blocks into hash entries
        text = hashHtmlBlocks(text);

        // Strip link definitions, store in hashes.
        text = stripLinkDefinitions(text);

        text = runBlockGamut(text);

        text = unescapeSpecialChars(text);

        return text + "\n";
    }

    private static final String LINK_PATTERN = String.format("" +
            "^[ ]{0,%d}\\[(.+)\\]:" +  // id = $1
            "[ \\t]*\n?[ \\t]*" +      // maybe *one* newline
            "<?(\\S+?)>?" +            // url = $2
            "[ \\t]*\n?[ \\t]*" +      // maybe one newline
            "(?:" +
            "(?<=\\s)" +               // lookbehind for whitespace
            "[\\x22(]" +
            "(.+?)" +                  // title = $3
            "[\\x22)]" +
            "[ \\t]*" +
            ")?" +                     // title is optional
            "(?:\\n+|\\Z)", TAB_WIDTH - 1);
    private final static Pattern LINK = Pattern.compile(LINK_PATTERN, Pattern.MULTILINE);

    private final ReplaceCallback linkCallback = new ReplaceCallback() {
        public String match(MatchResult match) {
            String linkId = match.group(1).toLowerCase();
            urls.put(linkId, encodeAmpsAndAngles(match.group(2)));
            if (match.group(3) != null && match.group(3).length() > 0) {
                titles.put(linkId, match.group(3).replace("\"", "&quot;"));
            }
            return "";
        }
    };

    private String stripLinkDefinitions(String text) {
        return replace(LINK, text, linkCallback);
    }

    private static final String BLOCK_TAGS_1 = "p|div|h[1-6]|blockquote|pre|table|dl|ol|ul|script|noscript|form|fieldset|iframe|math|ins|del";
    private static final String BLOCKS_NESTED_PATTERN = String.format("" +
            "(" +                      // save in $1
            "^" +                      // start of line (with MULTILINE)
            "<(%s)" +                  // start tag = $2
            "\\b" +                    // word break
            "(?>.*\\n)*?" +            // any number of lines, minimally matching
            "</\\2>" +                 // the matching end tag
            "[ \\t]*" +                // trailing spaces/tags
            "(?=\\n+|\\Z)" +           // followed by a newline or end of
            ")", BLOCK_TAGS_1);
    private static final Pattern BLOCKS_NESTED = Pattern.compile(BLOCKS_NESTED_PATTERN, Pattern.MULTILINE);

    private static final String BLOCK_TAGS_2 = "p|div|h[1-6]|blockquote|pre|table|dl|ol|ul|script|noscript|form|fieldset|iframe|math";
    private static final String BLOCKS_NESTED_LIBERAL_PATTERN = String.format("" +
            "(" +                      // save in $1
            "^" +                      // start of line (with MULTILINE)
            "<(%s)" +                  // start tag = $2
            "\\b" +                    // word break
            "(?>.*\\n)*?" +            // any number of lines, minimally matching
            ".*</\\2>" +                 // the matching end tag
            "[ \\t]*" +                // trailing spaces/tags
            "(?=\\n+|\\Z)" +           // followed by a newline or end of
            ")", BLOCK_TAGS_2);
    private static final Pattern BLOCKS_NESTED_LIBERAL = Pattern.compile(BLOCKS_NESTED_LIBERAL_PATTERN, Pattern.MULTILINE);

    private static final String BLOCKS_HR_PATTERN = String.format("" +
            "(?:" +
            "(?<=\\n\\n)" +       // Starting after a blank line
            "|" +                 // or
            "\\A\\n?" +           // the beginning of the doc
            ")" +
            "(" +                 // save in $1
            "[ ]{0,%d}" +
            "<(hr)" +             // start tag = $2
            "\\b" +               // word break
            "([^<>])*?" +
            "/?>" +               // the matching end tag
            "[ \\t]*" +
            "(?=\\n{2,}|\\Z)" +   // followed by a blank line or end of document
            ")", TAB_WIDTH - 1);
    private static final Pattern BLOCKS_HR = Pattern.compile(BLOCKS_HR_PATTERN);

    private static final String BLOCK_HTML_COMMENTS_PATTERN = String.format("" +
            "(?:" +
            "(?<=\\n\\n)" +       // Starting after a blank line
            "|" +                 // or
            "\\A\\n?" +           // the beginning of the doc
            ")" +
            "(" +                 // save in $1
            "[ ]{0,%d}" +
            "(?s:" +
            "<!" +
            "(--.*?--\\s*)+" +
            ">" +
            ")" +
            "[ \\t]*" +
            "(?=\\n{2,}|\\Z)" +    // followed by a blank line or end of document
            ")", TAB_WIDTH - 1);
    private static final Pattern BLOCK_HTML_COMMENTS = Pattern.compile(BLOCK_HTML_COMMENTS_PATTERN);

    private final ReplaceCallback htmlBlockCallback = new ReplaceCallback() {
        public String match(MatchResult match) {
            String text = match.group(1);
            String key = Integer.toString(text.hashCode());
            htmlBlocks.put(key, text);
            return "\n\n" + key + "\n\n";
        }
    };

    /**
     * Hashify HTML blocks:
     * We only want to do this for block-level HTML tags, such as headers,
     * lists, and tables. That's because we still want to wrap &lt;p&gt;s around
     * "paragraphs" that are wrapped in non-block-level tags, such as anchors,
     * phrase emphasis, and spans. The list of tags we're looking for is
     * hard-coded.
     *
     * @param text Markdown
     * @return (X)HTML
     */
    private String hashHtmlBlocks(String text) {
        /*
         First, look for nested blocks, e.g.:
        <div>
            <div>
            tags for inner block must be indented.
            </div>
        </div>

         The outermost tags must start at the left margin for this to match, and
         the inner nested divs must be indented.
         We need to do this before the next, more liberal match, because the next
         match will start at the type `<div>` and stop at the type `</div>`.
        */
        text = replace(BLOCKS_NESTED, text, htmlBlockCallback);

        // Now match more liberally, simply from `\n<tag>` to `</tag>\n`
        text = replace(BLOCKS_NESTED_LIBERAL, text, htmlBlockCallback);

        // Special case just for <hr />. It was easier to make a special case than
        // to make the other regex more complicated.
        text = replace(BLOCKS_HR, text, htmlBlockCallback);

        // Special case for standalone HTML comments:
        text = replace(BLOCK_HTML_COMMENTS, text, htmlBlockCallback);

        return text;
    }

    private static final Pattern BLOCK_1 = Pattern.compile("^[ ]{0,2}([ ]?\\*[ ]?){3,}[ \\t]*$", Pattern.MULTILINE);
    private static final Pattern BLOCK_2 = Pattern.compile("^[ ]{0,2}([ ]?-[ ]?){3,}[ \\t]*$", Pattern.MULTILINE);
    private static final Pattern BLOCK_3 = Pattern.compile("^[ ]{0,2}([ ]?_[ ]?){3,}[ \\t]*$", Pattern.MULTILINE);

    private static final String HR = "<hr" + EMPTY_ELEMENT_SUFFIX + "\n";

    /**
     * These are all the transformations that form block-level
     * tags like paragraphs, headers, and list items.
     *
     * @param text Markdown
     * @return (X)HTML
     */
    private String runBlockGamut(String text) {
        text = doHeaders(text);

        // do horizontal rules
        text = replace(BLOCK_1, text, HR);
        text = replace(BLOCK_2, text, HR);
        text = replace(BLOCK_3, text, HR);

        text = doLists(text);
        text = doCodeBlocks(text);
        text = doBlockQuotes(text);

        // We already ran hashHtmlBlocks() before, in Markdown(), but that
        // was to escape raw HTML in the original Markdown source. This time,
        // we're escaping the markup we've just created, so that we don't wrap
        // <p> tags around block-level tags.
        text = hashHtmlBlocks(text);

        text = formParagraphs(text);

        return text;
    }

    /**
     * These are all the transformations that occur *within* block-level
     * tags like paragraphs, headers, and list items.
     *
     * @param text Markdown
     * @return (X)HTML
     */
    private String runSpanGamut(String text) {
        text = doCodeSpans(text);

        text = escapeSpecialCharsWithinTagAttributes(text);
        text = encodeBackslashEscapes(text);

        // Process anchor and image tags. Images must come type,
        // because ![foo][f] looks like an anchor.
        text = doImages(text);
        text = doAnchors(text);

        // Make links out of things like `<http://example.com/>`
        // Must come after doAnchors(), because you can use < and >
        // delimiters in inline links like [this](<url>).
        text = doAutoLinks(text);

        // Fix unencoded ampersands and <'s:
        text = encodeAmpsAndAngles(text);

        text = doItalicsAndBold(text);

        // do hard breaks
        text = replace(LINE_BREAK, text, LINE_BREAK_ELEMENT);

        return text;
    }

    private static final Pattern HTML_TOKENS = Pattern.compile("" +
            "(?s:<!(?:--.*?--\\s*)+>)|(?s:<\\?.*?\\?>)|" +
            repeat("(?:<[a-z\\/!$](?:[^<>]|", NESTED_BRACKET_DEPTH) +
            repeat(")*>)", NESTED_BRACKET_DEPTH), Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    /**
     * Tokenize HTML
     *
     * @param text String containing HTML markup.
     * @return An array of the tokens comprising the input String. Each token is
     *         either a tag (possibly with nested, tags contained therein, such
     *         as &lt;a href="&lt;MTFoo&gt;"&gt;, or a run of text between tags. Each element of the
     *         array is a two-element array; the type is either 'tag' or 'text'; the value is
     *         the actual value.
     */
    private List<Token> tokenizeHtml(String text) {
        // Regular expression derived from the _tokenize() subroutine in
        // Brad Choate's MTRegex plugin.
        // http://www.bradchoate.com/past/mtregex.php
        return tokenize(HTML_TOKENS, text, new TokenizeCallback<Token>() {
            public Token text(String text) {
                return new Token(TokenType.TEXT, text);
            }

            public Token match(MatchResult match) {
                return new Token(TokenType.TAG, match.group());
            }
        });
    }

    private String escape(String text, String s) {
        String rep = ESCAPE_TABLE.get(s);
        return rep == null ? text : text.replace(s, rep);
    }

    private static final Pattern CODE = Pattern.compile("(?<=.)</?code>(?=.)");

    /**
     * Within tags -- meaning between &lt; and &gt; -- encode [\ ` * _] so they
     * don't conflict with their use in Markdown for code, italics and strong.
     * We're replacing each such character with its corresponding MD5 checksum
     * value; this is likely overkill, but it should prevent us from colliding
     * with the escape values by accident.
     *
     * @param text Markdown
     * @return (X)HTML
     */
    private String escapeSpecialCharsWithinTagAttributes(String text) {
        StringBuilder sb = new StringBuilder(text.length());

        // now, rebuild text from the tokens
        List<Token> tokens = tokenizeHtml(text);
        for (Token token : tokens) {
            String value = token.value;
            if (token.type == TokenType.TAG) {
                value = escape(value, "\\");
                value = replace(CODE, value, ESCAPE_TABLE.get("`"));
                value = escapeBoldItalic(value);
            }
            sb.append(value);
        }

        return sb.toString();
    }

    private static final String ANCHOR_REF_PATTERN = String.format("" +
            "(" +            // wrap whole match in $1
            "\\[" +
            "(%s)" +         // link text = $2
            "\\]" +
            "[ ]?" +         // one optional space
            "(?:\\n[ ]*)?" + // one optional newline followed by spaces
            "\\[" +
            "(.*?)" +        // id = $3
            "\\]" +
            ")", NESTED_BRACKETS_PATTERN);
    private static final Pattern ANCHOR_REF = Pattern.compile(ANCHOR_REF_PATTERN, Pattern.DOTALL);
    private final ReplaceCallback anchorRefCallback = new ReplaceCallback() {
        public String match(MatchResult match) {
            String wholeMatch = match.group(1);
            String linkText = match.group(2);
            String linkId = match.group(3).toLowerCase();
            String url;
            String title;

            String output;

            // for shortcut links like [this][].
            if (empty(linkId)) {
                linkId = linkText.toLowerCase();
            }

            if (urls.containsKey(linkId)) {
                url = urls.get(linkId);
                url = encodeProblemUrlChars(url);
                StringBuilder sb = new StringBuilder();
                sb.append("<a href=\"");
                sb.append(url);
                sb.append("\"");
                if (titles.containsKey(linkId)) {
                    title = titles.get(linkId);
                    title = escapeBoldItalic(title);
                    sb.append(" title=\"");
                    sb.append(title);
                    sb.append("\"");
                }
                sb.append(">");
                sb.append(linkText);
                sb.append("</a>");
                output = sb.toString();
            } else {
                output = wholeMatch;
            }

            return output;
        }
    };

    private static final String ANCHOR_INLINE_PATTERN = String.format("" +
            "(" +          // wrap whole match in $1
            "\\[" +
            "(%s)" +       // link text = $2
            "\\]" +
            "\\(" +        // literal paren
            "[ \\t]*" +
            "(%s)" +       // href = $3
            "[ \\t]*" +
            "(" +          // $4
            "(['\\x22])" + // quote char = $5
            "(.*?)" +      // Title = $6
            "\\5" +        // matching quote
            "[ \\t]*" +    // ignore any spaces/tabs between closing quote and )
            ")?" +         // title is optional
            "\\)" +
            ")", NESTED_BRACKETS_PATTERN, NESTED_PARENS_PATTERN);
    private static final Pattern ANCHOR_INLINE = Pattern.compile(ANCHOR_INLINE_PATTERN, Pattern.DOTALL);
    private final ReplaceCallback anchorInlineCallback = new ReplaceCallback() {
        public String match(MatchResult match) {
            String linkText = match.group(2);
            String url = match.group(3);
            String title = match.group(6);

            url = escapeBoldItalic(url);
            if (url.startsWith("<") && url.endsWith(">")) {
                // remove <>'s surrounding URL, if present
                url = url.substring(1, url.length() - 1);
            }
            url = encodeProblemUrlChars(url);

            StringBuilder output = new StringBuilder();
            output.append("<a href=\"");
            output.append(url);
            output.append("\"");

            if (!empty(title)) {
                title = title.replace("\"", "&quot;");
                title = escapeBoldItalic(title);
                output.append(" title=\"");
                output.append(title);
                output.append("\"");
            }

            output.append(">");
            output.append(linkText);
            output.append("</a>");

            return output.toString();
        }
    };

    private static final Pattern EMBEDDED_NEWLINES = Pattern.compile("[ ]*\\n[ ]*");
    private static final Pattern ANCHOR_REF_SHORTCUT = Pattern.compile("" +
            "(" + // wrap whole match in $1
            "\\[" +
            "([^\\[\\]]+)" + // link text = $2; can't contain '[' or ']'
            "\\]" +
            ")", Pattern.DOTALL);
    private final ReplaceCallback anchorRefShortcutCallback = new ReplaceCallback() {
        public String match(MatchResult match) {
            String wholeMatch = match.group(1);
            String linkText = match.group(2);
            String linkId = replace(EMBEDDED_NEWLINES, linkText, " ");

            String result;
            if (urls.containsKey(linkId)) {
                String url = urls.get(linkId);

                url = escapeBoldItalic(url);
                url = encodeProblemUrlChars(url);
                StringBuilder sb = new StringBuilder();
                sb.append("<a href=\"");
                sb.append(url);
                sb.append("\"");

                if (titles.containsKey(linkId)) {
                    String title = titles.get(linkId);
                    title = escapeBoldItalic(title);
                    sb.append(" title=\"");
                    sb.append(title);
                    sb.append("\"");
                }

                sb.append(">");
                sb.append(linkText);
                sb.append("</a>");
                result = sb.toString();
            } else {
                result = wholeMatch;
            }

            return result;
        }
    };

    /**
     * escapes Bold [ * ] and Italic [ _ ] characters
     *
     * @param s Markdown
     * @return Markdown with bold and italic special characters escaped
     */
    private String escapeBoldItalic(String s) {
        s = escape(s, "*");
        s = escape(s, "_");
        return s;
    }

    /**
     * Turn Markdown link shortcuts [link text](url "title") or [link text][id] into HTML anchor tags.
     *
     * @param text Markdown
     * @return (X)HTML
     */
    private String doAnchors(String text) {
        // First, handle reference-style links: [link text] [id]
        text = replace(ANCHOR_REF, text, anchorRefCallback);

        // Next, inline-style links: [link text](url "optional title") or [link text](url "optional title")
        text = replace(ANCHOR_INLINE, text, anchorInlineCallback);

        // Last, handle reference-style shortcuts: [link text]
        // These must come last in case you've also got [link test][1]
        // or [link test](/foo)
        text = replace(ANCHOR_REF_SHORTCUT, text, anchorRefShortcutCallback);

        return text;
    }

    private static final Pattern LINK_COLON = Pattern.compile(":(?!\\d{2,})");

    /**
     * encodes problem characters in URLs, such as
     * ' () [] * _ :
     * this is to avoid problems with markup later
     *
     * @param url URL
     * @return Escaped URL
     */
    private String encodeProblemUrlChars(String url) {
        if (ENCODE_PROBLEM_URL_CHARACTERS) {
            url = url.replace("'", "%27");
            url = url.replace("(", "%28");
            url = url.replace(")", "%29");
            url = url.replace("[", "%5B");
            url = url.replace("]", "%5D");
            url = url.replace("*", "%2A");
            url = url.replace("_", "%5F");
            if (url.length() > 7 && url.indexOf(":") >= 7) {
                // match any colons in the body of the URL that are NOT followed by 2 or more numbers
                url = url.substring(0, 7) + replace(LINK_COLON, url, "%3A");
            }
        }
        return url;
    }

    private static final Pattern IMAGES_REF = Pattern.compile("" +
            "(" +            // wrap whole match in $1
            "!\\[" +
            "(.*?)" +        // alt text = $2
            "\\]" +
            "[ ]?" +         // one optional space
            "(?:\\n[ ]*)?" + // one optional newline followed by spaces
            "\\[" +
            "(.*?)" +        // id = $3
            "\\]" +
            ")", Pattern.DOTALL);
    private final ReplaceCallback imageReferenceCallback = new ReplaceCallback() {
        public String match(MatchResult match) {
            String wholeMatch = match.group(1);
            String altText = match.group(2);
            String linkId = match.group(3).toLowerCase();
            String url;
            String title;
            String output;

            // for shortcut links like ![this][].
            if (empty(linkId)) {
                linkId = altText.toLowerCase();
            }

            altText = altText.replace("\"", "&quot;");

            if (urls.containsKey(linkId)) {
                url = urls.get(linkId);
                url = encodeProblemUrlChars(url);

                StringBuilder sb = new StringBuilder();
                sb.append("<img src=\"");
                sb.append(url);
                sb.append("\" alt=\"");
                sb.append(altText);
                sb.append("\"");

                if (titles.containsKey(linkId)) {
                    title = titles.get(linkId);
                    title = escapeBoldItalic(title);

                    sb.append(" title=\"");
                    sb.append(title);
                    sb.append("\"");
                }

                sb.append(EMPTY_ELEMENT_SUFFIX);
                output = sb.toString();
            } else {
                // If there's no such link ID, leave intact:
                output = wholeMatch;
            }

            return output;
        }
    };

    private static final Pattern ENCLOSING_LT_GT = Pattern.compile("^<(.*)>$");

    private static final String IMAGES_INLINE_PATTERN = String.format("" +
            "(" +          // wrap whole match in $1
            "!\\[" +
            "(.*?)" +      // alt text = $2
            "\\]" +
            "\\s?" +       // one optional whitespace character
            "\\(" +        // literal paren
            "[ \\t]*" +
            "(%s)" +       // href = $3
            "[ \\t]*" +
            "(" +          // $4
            "(['\\x22])" + // quote char = $5
            "(.*?)" +      // title = $6
            "\\5" +        // matching quote
            "[ \\t]*" +
            ")?" +         // title is optional
            "\\)" +
            ")", NESTED_PARENS_PATTERN);
    private static final Pattern IMAGES_INLINE = Pattern.compile(IMAGES_INLINE_PATTERN, Pattern.DOTALL);
    private final ReplaceCallback imageInlineCallback = new ReplaceCallback() {
        public String match(MatchResult match) {
            String alt = match.group(2);
            String url = match.group(3);
            String title = match.group(6);

            alt = alt.replace("\"", "&quot;");
            if (title != null) {
                title = title.replace("\"", "&quot;");
            }
            url = encodeProblemUrlChars(url);

            // remove <>'s surrounding URL, if present
            url = replace(ENCLOSING_LT_GT, url, "$1");

            StringBuilder output = new StringBuilder();
            output.append("<img src=\"");
            output.append(url);
            output.append("\" alt=\"");
            output.append(alt);
            output.append("\"");

            if (!empty(title)) {
                title = escapeBoldItalic(title);
                output.append(" title=\"");
                output.append(title);
                output.append("\"");
            }

            output.append(EMPTY_ELEMENT_SUFFIX);

            return output.toString();
        }
    };

    /**
     * Turn Markdown image shortcuts into <img> tags.
     *
     * @param text Markdown
     * @return (X)HTML
     */
    private String doImages(String text) {
        // First, handle reference-style labeled images: ![alt text][id]
        text = replace(IMAGES_REF, text, imageReferenceCallback);

        // Next, handle inline images:  ![alt text](url "optional title")
        // don't forget: encode * and _
        text = replace(IMAGES_INLINE, text, imageInlineCallback);

        return text;
    }

    // profiler says this one is expensive
    private static final Pattern HEADER_1 = Pattern.compile("^(.+?)[ \t]*\n=+[ \t]*\n+", Pattern.MULTILINE);
    private final ReplaceCallback header1Callback = new ReplaceCallback() {
        public String match(MatchResult match) {
            return String.format("<h1>%s</h1>\n\n", runSpanGamut(match.group(1)));
        }
    };

    private static final Pattern HEADER_2 = Pattern.compile("^(.+?)[ \t]*\n-+[ \t]*\n+", Pattern.MULTILINE);
    private final ReplaceCallback header2Callback = new ReplaceCallback() {
        public String match(MatchResult match) {
            return String.format("<h2>%s</h2>\n\n", runSpanGamut(match.group(1)));
        }
    };

    private static final Pattern HEADER_HASH = Pattern.compile("" +
            "^(#{1,6})" + // $1 = string of" + //'s
            "[ \\t]*" +
            "(.+?)" +       // $2 = Header text
            "[ \\t]*" +
            "#*" +        // optional closing" + //'s (not counted)
            "\\n+", Pattern.MULTILINE);
    private final ReplaceCallback headerHashCallback = new ReplaceCallback() {
        public String match(MatchResult match) {
            String headerSig = match.group(1);
            String headerText = match.group(2);
            return String.format("<h%d>%s</h%d>\n\n", headerSig.length(), runSpanGamut(headerText), headerSig.length());
        }
    };

    private String doHeaders(String text) {
        /*
        Setext-style headers:

        Header 1
        ========

        Header 2
        --------
        */
        text = replace(HEADER_1, text, header1Callback);
        text = replace(HEADER_2, text, header2Callback);

        /*
         atx-style headers:
            # Header 1
            ## Header 2
            ## Header 2 with closing hashes ##
            ...
            ###### Header 6
        */
        text = replace(HEADER_HASH, text, headerHashCallback);

        return text;
    }

    private static final String WHOLE_LIST_PATTERN = String.format("" +
            "(" +          // $1 = whole list
            "(" +          // $2
            "[ ]{0,%d}" +
            "(%s)" +       // $3 = type list item marker
            "[ \\t]+" +
            ")" +
            "(?s:.+?)" +
            "(" +          // $4
            "\\z" +
            "|" +
            "\\n{2,}" +
            "(?=\\S)" +
            "(?!" +        // Negative lookahead for another list item marker
            "[ \\t]*" +
            "%<s[ \\t]+" +
            ")" +
            ")" +
            ")", TAB_WIDTH - 1, MARKER_ANY_PATTERN);

    private static final Pattern LIST_NESTED = Pattern.compile("^" + WHOLE_LIST_PATTERN, Pattern.MULTILINE);

    // profiler says this one is expensive
    private static final Pattern LIST_TOP_LEVEL = Pattern.compile("(?:(?<=\\n\\n)|\\A\\n?)" + WHOLE_LIST_PATTERN, Pattern.MULTILINE);

    private static final Pattern TWO_PLUS_NEWLINES = Pattern.compile("\\n{2,}");
    private static final Pattern TWO_PLUS_NEWLINES_Z = Pattern.compile("\\n{2,}\\z", Pattern.MULTILINE);

    private final ReplaceCallback listCallback = new ReplaceCallback() {
        public String match(MatchResult match) {
            String list = match.group(1);
            String listType = matches(MARKER_UL, match.group(3)) ? "ul" : "ol";
            String output;

            // Turn double returns into triple returns, so that we can make a
            // paragraph for the last item in a list, if necessary:
            list = replace(TWO_PLUS_NEWLINES, list, "\n\n\n");
            output = processListItems(list, MARKER_ANY_PATTERN);

            // from Markdown 1.0.2b8 -- not doing this for now
            //
            // Trim any trailing whitespace, to put the closing `</$list_type>`
            // up on the preceding line, to get it past the current stupid
            // HTML block parser. This is a hack to work around the terrible
            // hack that is the HTML block parser.
            //
            //output = Regex.Replace(output, @"\s+$", "");
            //output = string.Format("<{0}>{1}</{0}>\n", listType, output);
            output = String.format("<%s>\n%s</%s>\n", listType, output, listType);

            return output;
        }
    };

    private String doLists(String text) {
        // Re-usable pattern to match any entirel ul or ol list:

        // We use a different prefix before nested lists than top-level lists.
        // See extended comment in _ProcessListItems().
        if (listLevel > 0) {
            text = replace(LIST_NESTED, text, listCallback);
        } else {
            text = replace(LIST_TOP_LEVEL, text, listCallback);
        }

        return text;
    }

    /**
     * Process the contents of a single ordered or unordered list, splitting it
     * into individual list items.
     *
     * @param list   List markdown
     * @param marker List item arker
     * @return (X)HTML
     */
    private String processListItems(String list, String marker) {
        /*
            The listLevel global keeps track of when we're inside a list.
            Each time we enter a list, we increment it; when we leave a list,
            we decrement. If it's zero, we're not in a list anymore.

            We do this because when we're not inside a list, we want to treat
            something like this:

                I recommend upgrading to version
                8. Oops, now this line is treated
                as a sub-list.

            As a single paragraph, despite the fact that the value line starts
            with a digit-period-space sequence.

            Whereas when we're inside a list (or sub-list), that line will be
            treated as the start of a sub-list. What a kludge, huh? This is
            an aspect of Markdown's syntax that's hard to parse perfectly
            without resorting to mind-reading. Perhaps the solution is to
            change the syntax rules such that sub-lists must start with a
            starting cardinal number; e.g. "1." or "a.".
        */

        listLevel++;

        // Trim trailing blank lines:
        list = replace(TWO_PLUS_NEWLINES_Z, list, "\n");

        String pattern = String.format("" +
                "(\\n)?" +       // leading line = $1
                "(^[ \\t]*)" +   // leading whitespace = $2
                "(%s)[ \\t]+" +  // list marker = $3
                "((?s:.+?)" +    // list item text = $4
                "(\\n{1,2}))" +
                "(?=\\n*(\\z|\\2(%<s)[ \t]+))", marker);

        list = replace(pattern, Pattern.MULTILINE, list, listCallback2);
        listLevel--;

        return list;
    }

    private static final Pattern TRAILING_NEWLINES = Pattern.compile("\\n+$", Pattern.MULTILINE);

    private final ReplaceCallback listCallback2 = new ReplaceCallback() {
        public String match(MatchResult match) {
            String item = match.group(4);
            String leadingLine = match.group(1);

            if (!empty(leadingLine) || find(TWO_PLUS_NEWLINES, item)) {
                item = runBlockGamut(outdent(item));
            } else {
                // Recursion for sub-lists:
                item = doLists(outdent(item));
                item = replace(TRAILING_NEWLINES, item, "");
                item = runSpanGamut(item);
            }

            return String.format("<li>%s</li>\n", item);
        }
    };

    private static final String CODE_BLOCK_PATTERN = String.format("" +
            "(?:\\n\\n|\\A)" +
            "(" +                         // $1 = the code block -- one or more lines, starting with a space/tab
            "(?:" +
            "(?:[ ]{%d}|\\t)" +           // Lines must start with a tab or a tab-width of spaces
            ".*\\n+" +
            ")+" +
            ")" +
            "((?=^[ ]{0,%<d}\\S)|\\Z)",   // Lookahead for non-space at line-start, or end of doc",
            TAB_WIDTH);
    private static final Pattern CODE_BLOCK = Pattern.compile(CODE_BLOCK_PATTERN, Pattern.MULTILINE);

    private static final Pattern LEADING_NEWLINES = Pattern.compile("^\\n+");
    private static final Pattern TRAILING_NEWLINES_Z = Pattern.compile("\\n+\\z");

    private final ReplaceCallback codeBlockCallback = new ReplaceCallback() {
        public String match(MatchResult match) {
            String codeBlock = match.group(1);

            codeBlock = encodeCode(outdent(codeBlock));
            codeBlock = detab(codeBlock);
            codeBlock = replace(LEADING_NEWLINES, codeBlock, "");
            codeBlock = replace(TRAILING_NEWLINES_Z, codeBlock, "");

            return String.format("\n\n<pre><code>%s\n</code></pre>\n\n", codeBlock);
        }
    };

    private String doCodeBlocks(String text) {
        return replace(CODE_BLOCK, text, codeBlockCallback);
    }

    private static final Pattern CODE_SPAN = Pattern.compile("" +
            "(?<!\\\\)" + // Character before opening ` can't be a backslash
            "(`+)" +      // $1 = Opening run of `
            "(.+?)" +     // $2 = The code block
            "(?<!`)" +
            "\\1" +
            "(?!`)", Pattern.DOTALL);

    private static final Pattern LEADING_WHITESPACE = Pattern.compile("^[ \\t]*");
    private static final Pattern TRAILING_WHITESPACE = Pattern.compile("[ \\t]*$");

    private final ReplaceCallback codeSpanCallback = new ReplaceCallback() {
        public String match(MatchResult match) {
            String s = match.group(2);
            s = replace(LEADING_WHITESPACE, s, "");
            s = replace(TRAILING_WHITESPACE, s, "");
            s = encodeCode(s);

            return String.format("<code>%s</code>", s);
        }
    };

    private String doCodeSpans(String text) {
        /*
            Backtick quotes are used for <code></code> spans.
            You can use multiple backticks as the delimiters if you want to
            include literal backticks in the code span. So, this input:

            Just type ``foo `bar` baz`` at the prompt.

            Will translate to:

              <p>Just type <code>foo `bar` baz</code> at the prompt.</p>

            There's no arbitrary limit to the number of backticks you
            can use as delimters. If you need three consecutive backticks
            in your code, use four for delimiters, etc.

            You can use spaces to get literal backticks at the edges:

            ... type `` `bar` `` ...

            Turns to:

             ... type <code>`bar`</code> ...
        */

        return replace(CODE_SPAN, text, codeSpanCallback);
    }

    /**
     * encode/escape certain characters inside Markdown code runs.
     * The point is that in code, these characters are literals, and lose their
     * special Markdown meanings.
     *
     * @param code Markdown code
     * @return Markdown with special characters inside code block escaped as they are literals
     */
    private String encodeCode(String code) {
        // encode all ampersands; HTML entities are not
        // entities within a Markdown code span.
        code = code.replace("&", "&amp;");

        // do the angle bracket song and dance
        code = code.replace("<", "&lt;");
        code = code.replace(">", "&gt;");

        // Now, escape characters that are magic in Markdown
        code = escape(code, "*");
        code = escape(code, "_");
        code = escape(code, "{");
        code = escape(code, "}");
        code = escape(code, "[");
        code = escape(code, "]");
        code = escape(code, "\\");

        return code;
    }

    // profiler says this one is expensive
    private static final Pattern STRONG = Pattern.compile(BOLD_PATTERN, Pattern.DOTALL);

    // profiler says this one is expensive
    private static final Pattern ITALICS = Pattern.compile(ITALIC_PATTERN, Pattern.DOTALL);

    private String doItalicsAndBold(String text) {
        // <strong> must go type:
        text = replace(STRONG, text, BOLD_REPLACE);

        // Then <em>:
        text = replace(ITALICS, text, ITALIC_REPLACE);

        return text;
    }

    private static final Pattern BLOCK_QUOTE = Pattern.compile("" +
            "(" +                // Wrap whole match in $1
            "(" +
            "^[ \\t]*>[ \\t]?" + // '>' at the start of a line
            ".+\\n" +            // rest of the type line
            "(.+\\n)*" +         // subsequent consecutive lines
            "\\n*" +             // blanks
            ")+" +
            ")", Pattern.MULTILINE);

    private static final Pattern LEADING_QUOTE = Pattern.compile("^[ \t]*>[ \t]?", Pattern.MULTILINE);
    private static final Pattern BLANK_LINE = Pattern.compile("^[ \t]+$", Pattern.MULTILINE);
    private static final Pattern SPACE_PRE = Pattern.compile("(\\s*<pre>.+?</pre>)", Pattern.DOTALL);
    private static final Pattern START_MULTI = Pattern.compile("^", Pattern.MULTILINE);

    private final ReplaceCallback blockQuoteCallback = new ReplaceCallback() {
        public String match(MatchResult match) {
            String bq = match.group(1);

            // trim one level of quoting
            bq = replace(LEADING_QUOTE, bq, "");

            // trim whitespace-only lines
            bq = replace(BLANK_LINE, bq, "");

            bq = runBlockGamut(bq);
            bq = replace(START_MULTI, bq, "  ");

            // These leading spaces screw with <pre> content, so we need to fix that:
            bq = replace(SPACE_PRE, bq, blockQuoteCallback2);

            return String.format("<blockquote>\n%s\n</blockquote>\n\n", bq);
        }
    };

    private static final Pattern PRE_SPACE = Pattern.compile("^  ", Pattern.MULTILINE);
    private final ReplaceCallback blockQuoteCallback2 = new ReplaceCallback() {
        public String match(MatchResult match) {
            String pre = match.group(1);
            return replace(PRE_SPACE, pre, "");
        }
    };

    private String doBlockQuotes(String text) {
        return replace(BLOCK_QUOTE, text, blockQuoteCallback);
    }

    private static final Pattern NEWLINES_MULTI = Pattern.compile("\\n{2,}", Pattern.MULTILINE);
    private static final Pattern LEADING_TABS = Pattern.compile("^([ \\t]*)");

    private String formParagraphs(String text) {
        // strip leading and trailing lines
        text = replace(LEADING_NEWLINES, text, "");
        text = replace(TRAILING_NEWLINES_Z, text, "");

        String[] paragraphs = NEWLINES_MULTI.split(text);

        // Wrap <p> tags.
        for (int i = 0; i < paragraphs.length; i++) {
            if (!htmlBlocks.containsKey(paragraphs[i])) {
                String block = paragraphs[i];

                block = runSpanGamut(block);
                block = replace(LEADING_TABS, block, "<p>");
                block += "</p>";

                paragraphs[i] = block;
            }
        }

        // Unhashify HTML blocks
        for (int i = 0; i < paragraphs.length; i++) {
            if (htmlBlocks.containsKey(paragraphs[i]))
                paragraphs[i] = htmlBlocks.get(paragraphs[i]);
        }

        return join("\n\n", paragraphs);
    }

    // profiler says this one is expensive
    private static final Pattern AUTO_LINK_BARE = Pattern.compile(
            "(^|\\s)(https?|ftp)(://[-A-Z0-9+&@#/%?=~_|\\[\\]\\(\\)!:,\\.;]*[-A-Z0-9+&@#/%=~_|\\[\\]])($|\\W)");

    private static final Pattern LINK_ESCAPE = Pattern.compile("<(https?|ftp)://[^>]+>");
    private final ReplaceCallback linkEscapeCallback = new ReplaceCallback() {
        public String match(MatchResult match) {
            return encodeProblemUrlChars(match.group());
        }
    };

    private static final Pattern HYPERLINK = Pattern.compile("<((https?|ftp):[^'\">\\s]+)>");
    private final ReplaceCallback hyperlinkCallback = new ReplaceCallback() {
        public String match(MatchResult match) {
            return String.format("<a href=\"%s\">%<s</a>", match.group(1));
        }
    };

    private static final Pattern LINK_EMAIL = Pattern.compile("" +
            "<" +
            "(?:mailto:)?" +
            "(" +
            "[-.\\w]+" +
            "@" +
            "[-a-z0-9]+(\\.[-a-z0-9]+)*\\.[a-z]+" +
            ")" +
            ">", Pattern.CASE_INSENSITIVE);
    private final ReplaceCallback linkEmailCallback = new ReplaceCallback() {
        public String match(MatchResult match) {
            String email = unescapeSpecialChars(match.group(1));

            /*
                Input: an email address, e.g. "foo@example.com"

                Output: the email address as a mailto link, with each character
                        of the address encoded as either a decimal or hex entity, in
                        the hopes of foiling most address harvesting spam bots. E.g.:

                  <a href="&#x6D;&#97;&#105;&#108;&#x74;&#111;:&#102;&#111;&#111;&#64;&#101;
                    x&#x61;&#109;&#x70;&#108;&#x65;&#x2E;&#99;&#111;&#109;">&#102;&#111;&#111;
                    &#64;&#101;x&#x61;&#109;&#x70;&#108;&#x65;&#x2E;&#99;&#111;&#109;</a>

                Based by a filter by Matthew Wickline, posted to the BBEdit-Talk
                mailing list: <http://tinyurl.com/yu7ue>

             */
            email = "mailto:" + email;

            // leave ':' alone (to spot mailto: later)
            email = replace(EMAIL_COLON, email, encodeEmailCallback);

            email = String.format("<a href=\"%s\">%<s</a>", email);

            // strip the mailto: from the visible part
            email = replace(EMAIL_MAILTO, email, "\">");
            return email;
        }
    };

    private static final Pattern EMAIL_MAILTO = Pattern.compile("\">.+?:");

    private final Random random = new Random();
    private static final Pattern EMAIL_COLON = Pattern.compile("([^:])");
    private final ReplaceCallback encodeEmailCallback = new ReplaceCallback() {
        public String match(MatchResult match) {
            char c = match.group(1).charAt(0);

            int r = random.nextInt(101);

            // Original author note:
            // Roughly 10% raw, 45% hex, 45% dec
            // '@' *must* be encoded. I insist.
            if (r > 90 && c != '@') {
                return Character.toString(c);
            } else if (r < 45) {
                return String.format("&#x%d;", (int) c);
            } else {
                return String.format("&#x%d;", (int) c);
            }
        }
    };

    private String doAutoLinks(String text) {
        if (AUTO_HYPERLINK) {
            // fixup arbitrary URLs by adding Markdown < > so they get linked as well
            // note that at this point, all other URL in the text are already hyperlinked as <a href=""></a>
            // *except* for the <http://www.foo.com> case
            text = replace(AUTO_LINK_BARE, text, "$1<$2$3>$4");
        }

        text = replace(LINK_ESCAPE, text, linkEscapeCallback);

        // Hyperlinks: <http://foo.com>
        text = replace(HYPERLINK, text, hyperlinkCallback);

        if (LINK_EMAILS) {
            text = replace(LINK_EMAIL, text, linkEmailCallback);
        }

        return text;
    }

    private static final Pattern AMPS = Pattern.compile("&(?!#?[xX]?([0-9a-fA-F]+|\\w+);)");
    private static final Pattern ANGLES = Pattern.compile("<(?![A-Za-z/?\\$!])");

    /**
     * Smart processing for ampersands and angle brackets that need to be encoded.
     *
     * @param text Markdown
     * @return (X)HTML
     */
    public static String encodeAmpsAndAngles(String text) {
        // Ampersand-encoding based entirely on Nat Irons's Amputator MT plugin:
        // http://bumppo.net/projects/amputator/
        text = replace(AMPS, text, "&amp;");

        // Encode naked <'s
        text = replace(ANGLES, text, "&lt;");

        return text;
    }

    private String encodeBackslashEscapes(String value) {
        // Must process escaped backslashes type.
        for (Map.Entry<String, String> entry : BACKSLASH_ESCAPE_TABLE.entrySet()) {
            value = value.replace(entry.getKey(), entry.getValue());
        }
        return value;
    }

    /**
     * Swap back in all the special characters we've hidden.
     *
     * @param text Markdown
     * @return Markdown with special characters unescaped
     */
    private String unescapeSpecialChars(String text) {
        for (Map.Entry<String, String> entry : ESCAPE_TABLE.entrySet()) {
            text = text.replace(entry.getValue(), entry.getKey());
        }
        return text;
    }

    private static final String OUTDENT_PATTERN = String.format("^(\\t|[ ]{1,%d})", TAB_WIDTH);
    private static final Pattern OUTDENT = Pattern.compile(OUTDENT_PATTERN, Pattern.MULTILINE);

    /**
     * Remove one level of line-leading tabs or spaces
     *
     * @param block indented markdown
     * @return Markdown with one level of indenting removed
     */
    private String outdent(String block) {
        return replace(OUTDENT, block, "");
    }

    // profiler says this one is expensive
    private static final Pattern DETAB = Pattern.compile("^(.*?)(\\t+)", Pattern.MULTILINE);
    private static final ReplaceCallback DETAB_CALLBACK = new ReplaceCallback() {
        public String match(MatchResult match) {
            String leading = match.group(1);
            int tabCount = match.group(2).length();
            StringBuilder buf = new StringBuilder(leading);
            int spaceCount = TAB_WIDTH - leading.length() % TAB_WIDTH + ((tabCount - 1) * TAB_WIDTH);
            while (spaceCount-- > 0) {
                buf.append(' ');
            }
            return buf.toString();
        }
    };

    private String detab(String text) {
        // Inspired from a post by Bart Lateur:
        // http://www.nntp.perl.org/group/perl.macperl.anyperl/154
        //
        // without a beginning of line anchor, the above has HIDEOUS performance
        // so I added a line anchor and we count the # of tabs beyond that.
        return replace(DETAB, text, DETAB_CALLBACK);
    }

    private enum TokenType {
        TEXT, TAG
    }

    private static class Token {
        private final TokenType type;
        private final String value;

        private Token(TokenType type, String value) {
            this.type = type;
            this.value = value;
        }

        public String toString() {
            return type + ": " + value;
        }
    }
}
