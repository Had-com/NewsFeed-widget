# Release notes

Plain-language notes shown to users right before they install an update — see
`docs/superpowers/specs/2026-09-11-release-notes-design.md` for the format and mechanism.
Oldest note first; append new entries at the end, never renumber or edit an old one. Each
bullet must be a single physical line, however long — the parser reads line-by-line, so a
soft-wrapped bullet gets silently truncated at the first line break.

## Note 1
- You'll now see what's new right before installing an update, with a short summary of what changed and why it's worth updating.

## Note 2
- New Share buttons: share an article's link straight from the widget, or share NewsFeed itself (or a direct download link) from the widget's footer.

## Note 3
- Articles are now marked read when you move on to another article, not the moment you tap one — so under "Unread only" the article you're reading no longer dims and vanishes while you're still on it.

## Note 4
- Fixed: articles without a description (most news flashes) are now also marked read only when you tap a different article, not the moment you tap them.
- New default feeds: a brand-new widget now starts with an updated set of Hebrew news and AI feeds.

## Note 5
- Settings now has "Add default feeds" (adds the built-in feeds you are missing, without duplicates) and "Reset to defaults" (replaces all your feeds with the built-in ones, after a confirmation); both take effect when you tap Save.

## Note 6
- In "Unread only", a just-read article now fades into dots before it disappears: its text turns half dotted, then fully dotted, during the last couple of seconds.

## Note 7
- The fade-out now turns the text to dots and erases them from the end before the article disappears.

## Note 8
- Telegram posts now show their whole text when you expand them, and "Load full article" no longer garbles them.

## Note 9
- NewsFeed Focus is now a setting: in widget settings choose "When I tap an article" and pick "Expand in place" or "Focus (enlarge)". The separate NewsFeed Focus widget is gone, so a Focus widget already on your home screen disappears when you update: add the standard NewsFeed widget and choose Focus. In Focus mode only the article you tap enlarges, and the other rows keep their normal size.

## Note 10
- Fixed: a Telegram post's link (e.g. "קישור לכתבה") now keeps its web address, so you can actually reach the linked page — it used to vanish, leaving only the label text. The headline shows just the label (e.g. "קישור לכתבה"); the address appears under the headline when you expand the post, and long addresses wrap without shrinking the text.

## Note 11
- Fixed: in Focus mode the zoom you set with − / + is now remembered, instead of snapping back to the default every time you tap another article.

## Note 12
- New: similar stories from different feeds are now grouped into one thread with a small +N badge. Tap the badge to open or close the thread; reading one article marks the whole story read. You can switch this off in the widget settings under "Group similar stories".

## Note 13
- Fixed: Focus mode no longer loses the article you tapped near the bottom of the list, and the same article coming from two feeds is now shown only once.

## Note 14
- Fixed: story threads now group articles only when their headlines share real content words, not just a reporter name or a channel label.

## Note 15
- New: "Keep articles for" in the widget settings now has a 15 minutes option, so the list can hold only the freshest news.

## Note 16
- Fixed: pressing Load more many times in some themes no longer breaks the widget; it now stops at the number of articles the widget can safely show.

## Note 17
- Fixed: opening a group of similar stories at the very bottom of a full list now shows its articles instead of only turning the + into a minus.

## Note 18
- Changed: the "Showing X of Y" line at the bottom of a full list now counts articles, the same way the number at the top does, instead of counting list rows.

## Note 19
- Fixed: tapping an article in Focus mode no longer jumps the list back to the top, so the enlarged article stays right where you tapped, also the last one; clearing focus keeps your place too.
- Faster: tapping an article now updates the widget about three times sooner, especially with similar-story grouping on.

## Note 20
- Fixed: the widget now refreshes in the background more reliably, and the footer tells you when it last updated.

## Note 21
- New: Settings → Report a problem lets you share a diagnostic report any time, and the app now keeps a log of which feeds failed to refresh and why.
