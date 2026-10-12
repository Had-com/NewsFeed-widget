package com.newsfeed.widget.data

/** One paragraph of the "Send this report to the developer?" dialog; [isWarning] paragraphs are drawn in an alert colour. */
data class ConfirmParagraph(val text: String, val isWarning: Boolean = false)

/**
 * The text of the send-confirmation dialog, as pure data so it is unit tested.
 *  - It says what is sent and that nothing is sent automatically.
 *  - It does NOT say where the report goes (no host name, no address): the user asked for the server
 *    address to stay out of the UI.
 *  - The feed-address warning is shown only when the "Include my feed addresses" checkbox is on; with the
 *    checkbox off the dialog does not mention feed addresses at all.
 */
fun sendConfirmationParagraphs(previewedAlready: Boolean, includeAddresses: Boolean): List<ConfirmParagraph> {
    val which = if (previewedAlready) "the report you just previewed. " else "the report you can read with Preview report. "
    val paragraphs = mutableListOf(
        ConfirmParagraph(
            "What is sent: " + which +
                "It holds the app version, your phone model and Android version, your widget settings, the feed names, " +
                "the refresh results, any recorded crashes and your note.",
        ),
    )
    if (includeAddresses) {
        paragraphs += ConfirmParagraph(
            "Warning: your feed addresses are included in this report (passwords and tokens are removed). " +
                "Check the preview first if any of your feeds is private.",
            isWarning = true,
        )
    }
    paragraphs += ConfirmParagraph("Nothing is sent automatically. This report is sent only because you tap Send.")
    return paragraphs
}
