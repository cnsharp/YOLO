package com.cnsharp.yolo.exp.links

import com.intellij.execution.filters.Filter
import com.intellij.ide.BrowserUtil

/**
 * Makes `http(s)://` URLs printed by agents clickable, opening them in the system browser.
 *
 * No project or index access is involved, so this filter is always on.
 */
class UrlLinkFilter : Filter {

    override fun applyFilter(text: String, entireLength: Int): Filter.Result? {
        if (text.isBlank() || isDiffLine(text)) return null
        val items = mutableListOf<Filter.ResultItem>()
        val matcher = URL_PATTERN.matcher(text)
        var guard = 0
        while (matcher.find() && guard++ < MAX_MATCHES_PER_LINE) {
            val url = matcher.group()
            // No reference to resolve: the only work is opening the browser, which runs on the EDT.
            val link = expLink { { BrowserUtil.browse(url) } }
            items.add(Filter.ResultItem(matcher.start(), matcher.end(), link, HIGHLIGHT_ATTRS))
        }
        return if (items.isEmpty()) null else Filter.Result(items)
    }
}
