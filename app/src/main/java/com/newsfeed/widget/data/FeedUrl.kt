package com.newsfeed.widget.data

/**
 * Feed addresses and the cleartext block. The app does not allow cleartext traffic, so an `http://`
 * feed could never load (`UnknownServiceException: CLEARTEXT communication ... not permitted`).
 * Almost every site that still lists an `http://` feed answers its `https://` twin, so every `http://`
 * address is requested as `https://`. Pure (no Android types), unit tested.
 */

private const val HTTP_PREFIX = "http://"

/**
 * Replaces only the scheme: an address that starts with `http://` (any letter case) becomes `https://`
 * with everything after it untouched (host case, port, path, query, fragment). Anything else, empty or
 * malformed input included, is returned as is. Never throws.
 */
fun upgradeToHttps(url: String): String =
    if (url.startsWith(HTTP_PREFIX, ignoreCase = true)) "https://" + url.substring(HTTP_PREFIX.length) else url

/** True when [upgradeToHttps] changes the (trimmed) address, i.e. it is a cleartext one. */
fun isUpgradedFeedUrl(storedUrl: String): Boolean = storedUrl.trim().let { upgradeToHttps(it) != it }

/**
 * The address a request is sent to for a stored feed address. The stored address (what the user typed,
 * the feed's identity) is never rewritten by a fetch.
 */
fun requestUrlFor(storedUrl: String): String = upgradeToHttps(storedUrl.trim())

private const val UPGRADE_DETAIL_PREFIX = "https upgrade of http feed: "

/**
 * An `http://` feed that was requested as `https://` and failed with a TLS or connect error: the report must
 * say that the https twin is what failed, so the reader does not look for a cleartext problem. Other
 * failures (an HTTP status, a DNS failure, a timeout, a body that is not a feed) are not blamed on the
 * upgrade and keep their own detail.
 */
fun describeUpgradeFailure(storedUrl: String, c: FeedLogClassification): FeedLogClassification {
    if (c.status != FeedLogStatus.NETWORK_ERROR || c.detail.startsWith("dns:")) return c
    if (!isUpgradedFeedUrl(storedUrl)) return c
    return c.copy(detail = (UPGRADE_DETAIL_PREFIX + c.detail).take(REFRESH_LOG_MAX_DETAIL))
}
