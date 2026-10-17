package net.velocity.agent.research;

import java.io.IOException;
import java.io.StringReader;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;

import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;

/** Bounded best-effort classification only; never renders HTML, returns extracted content, or loads linked resources. */
final class HtmlAccessDetector {

    private static final int MAX_SOURCE_CHARS = 65_536;
    private static final Pattern TITLE = Pattern
            .compile("sign.?in|log.?in|access denied|forbidden|captcha|not found|error");
    private static final Set<String> BLOCKS = Set.of(
            "br",
            "hr",
            "p",
            "div",
            "section",
            "article",
            "main",
            "header",
            "footer",
            "tr",
            "td",
            "th",
            "li",
            "ul",
            "ol",
            "table",
            "h1",
            "h2",
            "h3",
            "h4",
            "h5",
            "h6",
            "form");

    private HtmlAccessDetector() {
    }

    static boolean isAccessPage(String source) {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
        }
        if (source == null || source.isEmpty()) {
            return false;
        }
        try {
            String prefix = maskRawText(source.substring(0, Math.min(source.length(), MAX_SOURCE_CHARS)));
            var classifier = new Classifier(false);
            new ParserDelegator().parse(new StringReader(prefix), classifier, true);
            if (classifier.access || classifier.title.matched || classifier.visible.matched) {
                return true;
            }
            // An unknown standalone opening tag can be discarded before the legacy parser infers a body.
            // Retry attributes only inside a container. Raw script/style contents were masked before either classification pass.
            var attributes = new Classifier(true);
            new ParserDelegator()
                    .parse(new StringReader("<html><body>" + prefix + "</body></html>"), attributes, true);
            return attributes.access;
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (IOException | RuntimeException malformed) {
            return false;
        }
    }

    private static String maskRawText(String source) {
        char[] bounded = source.toCharArray();
        for (int position = 0; position < source.length();) {
            int start = source.indexOf('<', position);
            if (start < 0) {
                break;
            }
            if (source.startsWith("<!--", start)) {
                int end = source.indexOf("-->", start + 4);
                if (end < 0) {
                    break;
                }
                position = end + 3;
                continue;
            }
            int end = tagEnd(source, start);
            String raw = tag(source, start, "script")
                    ? "script"
                    : tag(source, start, "style") ? "style" : null;
            if (raw == null || end == source.length()) {
                position = Math.min(source.length(), end + 1);
                continue;
            }
            int closing = end + 1;
            while ((closing = source.indexOf('<', closing)) >= 0 && !tag(source, closing, "/" + raw)) {
                closing++;
            }
            int contentEnd = closing < 0 ? source.length() : closing;
            Arrays.fill(bounded, end + 1, contentEnd, ' ');
            position = closing < 0 ? source.length() : Math.min(source.length(), tagEnd(source, closing) + 1);
        }
        return new String(bounded);
    }

    private static boolean tag(String source, int offset, String name) {
        int boundary = offset + name.length() + 1;
        return source.regionMatches(true, offset + 1, name, 0, name.length()) && boundary < source.length()
                && (Character.isWhitespace(source.charAt(boundary)) || source.charAt(boundary) == '>'
                        || source.charAt(boundary) == '/');
    }

    private static int tagEnd(String source, int offset) {
        char quote = 0;
        for (int position = offset + 1; position < source.length(); position++) {
            char value = source.charAt(position);
            if (quote != 0) {
                if (value == quote) {
                    quote = 0;
                }
            } else if (value == '\'' || value == '"') {
                quote = value;
            } else if (value == '>') {
                return position;
            }
        }
        return source.length();
    }

    private static final class Classifier extends HTMLEditorKit.ParserCallback {

        final TextWindow title = new TextWindow(true), visible = new TextWindow(false);
        final boolean attributesOnly;
        boolean access, inTitle;
        int suppressed;

        Classifier(boolean attributesOnly) {
            this.attributesOnly = attributesOnly;
        }

        @Override
        public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
            inspect(tag, attributes);
            String name = tag.toString();
            if (name.equals("script") || name.equals("style")) {
                suppressed++;
            }
            if (name.equals("title")) {
                inTitle = true;
            }
            if (BLOCKS.contains(name) && suppressed == 0) {
                visible.add(" ");
            }
        }

        @Override
        public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
            // Unknown HTML5 tags can arrive here, including a closing callback carrying their original attributes.
            inspect(tag, attributes);
            if (BLOCKS.contains(tag.toString()) && suppressed == 0) {
                visible.add(" ");
            }
        }

        @Override
        public void handleEndTag(HTML.Tag tag, int position) {
            String name = tag.toString();
            if (name.equals("script") || name.equals("style")) {
                suppressed = Math.max(0, suppressed - 1);
            }
            if (name.equals("title")) {
                inTitle = false;
            }
            if (BLOCKS.contains(name) && suppressed == 0) {
                visible.add(" ");
            }
        }

        @Override
        public void handleText(char[] text, int position) {
            if (attributesOnly || suppressed != 0) {
                return;
            }
            String value = new String(text);
            if (inTitle) {
                title.add(value);
            }
            visible.add(value);
        }

        private void inspect(HTML.Tag tag, MutableAttributeSet attributes) {
            if (suppressed != 0) {
                return;
            }
            String name = tag.toString();
            access |= name.equals("input") && attribute(attributes, HTML.Attribute.TYPE).equals("password")
                    || name.equals("iframe")
                            && attribute(attributes, HTML.Attribute.SRC).contains("recaptcha")
                    || (" " + attribute(attributes, HTML.Attribute.CLASS) + " ").contains(" g-recaptcha ")
                    || attribute(attributes, HTML.Attribute.ID).equals("challenge-form");
        }

        private static String attribute(MutableAttributeSet attributes, HTML.Attribute name) {
            Object value = attributes.getAttribute(name);
            return value == null
                    ? ""
                    : value.toString().toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Z}]+", " ").strip();
        }
    }

    /** Rolling normalized text retains enough overlap for every marker without accumulating an HTML document. */
    private static final class TextWindow {

        private final boolean title;
        private final StringBuilder tail = new StringBuilder(256);
        boolean matched;

        TextWindow(boolean title) {
            this.title = title;
        }

        void add(String text) {
            if (matched) {
                return;
            }
            String normalized = text.toLowerCase(Locale.ROOT);
            for (int index = 0; index < normalized.length(); index++) {
                char value = normalized.charAt(index);
                if (Character.isWhitespace(value) || Character.isSpaceChar(value)) {
                    value = ' ';
                }
                if (value == ' ' && !tail.isEmpty() && tail.charAt(tail.length() - 1) == ' ') {
                    continue;
                }
                tail.append(value);
                if (tail.length() == 256) {
                    match();
                    if (matched) {
                        return;
                    }
                    tail.delete(0, tail.length() - 64);
                }
            }
            match();
        }

        private void match() {
            String text = tail.toString();
            matched = title
                    ? TITLE.matcher(text).find()
                    : text.contains("verify you are human") || text.contains("your request has been blocked")
                            || text.contains("this link has expired")
                            || text.contains("the link has expired");
        }
    }
}
