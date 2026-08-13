package com.cnsharp.yolo.panel

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ide.BrowserUtil
import com.jediterm.terminal.model.hyperlinks.HyperlinkFilter
import com.jediterm.terminal.model.hyperlinks.LinkInfo
import com.jediterm.terminal.model.hyperlinks.LinkResult
import com.jediterm.terminal.model.hyperlinks.LinkResultItem

/**
 * Makes `http(s)://` URLs printed by agents clickable, opening them in the system browser.
 *
 * This deliberately does NOT hide the YOLO pane (unlike the other filters' [yoloHyperlink]): a URL opens
 * an external browser, not the IDE editor, so there is no in-IDE destination to reveal.
 *
 * Public APIs only: JediTerm's [HyperlinkFilter] for the terminal link and IntelliJ's
 * [com.intellij.util.BrowserUtil] for navigation.
 *
 * Note: JediTerm may already provide a built-in URL filter; if so this is redundant but harmless — the
 * two simply produce equivalent links.
 */
class UrlLinkFilter : HyperlinkFilter {

    override fun apply(text: String): LinkResult? {
        if (text.isBlank() || isDiffLine(text)) return null
        val items = mutableListOf<LinkResultItem>()
        val matcher = URL_PATTERN.matcher(text)
        var guard = 0
        while (matcher.find() && guard++ < MAX_MATCHES_PER_LINE) {
            val url = matcher.group()
            val link = LinkInfo(
                Runnable {
                    ApplicationManager.getApplication().invokeLater { BrowserUtil.browse(url) }
                }
            )
            items.add(LinkResultItem(matcher.start(), matcher.end(), link))
        }
        return if (items.isEmpty()) null else LinkResult(items)
    }
}
