# Changelog

## 1.6.0
- Added configurable Vault or mineral-item wagers to duels, with escrow, winner payouts, refunds, and duel-menu choices that adapt to installed economy support.
- Added `/noitu leave` and a configurable idle timeout for public games; the last player to connect wins, while untouched games are cancelled.
- Updated duel syntax and documentation for duration, stake type, and stake amount.


## 1.5.0
- Public game chat interception is limited to opted-in participants; spectators can use normal chat freely.
- Added optional consensual Vault wagers to duels, quick stake choices in the duel menu, payout/refund handling, a configurable wager cap, and decimal precision validation.
- Prevented immediate two-phrase reversals that caused circular chains in public games and duels.
- Exposed an asynchronous leaderboard API and cached PlaceholderAPI fields for external menus, scoreboards, and holograms.
- Added focused gameplay rule tests and made release artifact naming derive from the pushed version tag.

## 1.4.0
- Changed `/wordchain stop` for regular players to a public vote using the existing one-third quorum and timeout; admins can stop immediately.
- Unified stop/reset proposals under a shared vote model and cancel pending votes safely when a round changes or its requester leaves.
- Updated the release workflow and plugin metadata for Java 25 / Paper 26 builds.

## 1.3.1
- Added a streaming JSON-array importer for the Vietnamese-dictionary-api `text` entries and enabled its 3.9 MB source by default.
- Existing installations add the source once through a config migration; admins can then remove it to opt out.
- Added parser tests for normalization, exactly two syllables, nested fields, and escaped Unicode.

## 1.3.0
- Changed player reset into a public vote for a fresh random round; one-third of online players must accept, one-third rejecting declines, and proposals expire.
- Kept duel phrase turns private to both duelists and prevented duel chat from being broadcast to the world.
- Removed bundled/shaded SQLite JDBC from the plugin JAR; Paper resolves it through the `libraries` plugin.yml field.
- Added automated unit tests for reset vote quorum and eligibility.

## 1.2.1
- Added a `wordchain.player` permission group for LuckPerms and separate permission nodes for the basic menu, leaderboard, duel, duel responses, and word reports.
- Documented the LuckPerms command to grant the basic player permissions to its default group.

## 1.2.0

- Add streaming import for the NhanAZ Vietnamese dictionary 0.4.0-beta release and cache successful source imports.
- Choose random viable opening phrases, avoid recent repeats, and restart public or duel rounds when no valid continuation remains.
- Index phrases by first syllable for fast continuation checks.
- Include upstream data-source and license notes.

## 1.1.0

- Target Paper 26.2 and Java 25.
- Add private two-player duels, configurable turn timers, and a one-hour match cap.
- Add streak-aware rewards, persistent streak statistics, and automatic new keywords.
- Add admin reward and player-stat menus, dictionary moderation, and a plugin API.
- Improve dictionary import and chat processing efficiency.
