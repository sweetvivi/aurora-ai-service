package com.hub.backend.modules.document;

/**
 * The slice of a document that is actually sent to the model.
 *
 * <p>Long documents are sent as their head and tail: setup lives at the beginning, conclusions and
 * numbers usually sit at the end, and a hard cut in the middle loses both. {@code truncated} travels
 * back to the page so a result can say it was produced from an excerpt.</p>
 *
 * @param text      prompt ready text, with an explicit 「中间省略」 marker when cut
 * @param truncated true when the whole document did not fit
 */
record DocumentExcerpt(String text, boolean truncated) {

    static DocumentExcerpt of(String content, int maxChars) {
        String text = content == null ? "" : content.strip();
        int length = text.codePointCount(0, text.length());
        if (length <= maxChars) {
            return new DocumentExcerpt(text, false);
        }
        int headChars = maxChars * 7 / 10;
        int tailChars = maxChars - headChars;
        int headEnd = text.offsetByCodePoints(0, headChars);
        int tailStart = text.offsetByCodePoints(0, length - tailChars);
        String omitted = "……（中间省略约 %,d 字）……".formatted(length - headChars - tailChars);
        return new DocumentExcerpt(
                text.substring(0, headEnd) + "\n\n" + omitted + "\n\n" + text.substring(tailStart),
                true);
    }
}
