# WordChain (Paper)

MIT licensed. See [LICENSE](LICENSE).

A Vietnamese word-chain minigame for Paper 26.2+ and Java 25. The plugin imports plain UTF-8 word/phrase lists in the background, stores normalized entries in SQLite, and uses an indexed phrase table for fast lookup. Wins and leaderboard totals persist across restarts.

## Build and install

Run `mvn package` with Java 25. Copy `target/word-chain-1.1.0.jar` into the server's `plugins` folder and restart. Edit `plugins/WordChain/config.yml`, then run `/wordchain reload`.

## Commands and permissions

| Command | Description | Permission |
| --- | --- | --- |
| `/wordchain` or `/wc` | Open the game menu | Any player |
| `/wordchain start [two-word phrase]` | Start a game | `wordchain.play` (default: everyone) |
| `/wordchain stop` | Stop the current game | `wordchain.play` |
| `/wordchain reset` | Clear current game state | `wordchain.play` |
| `/wordchain top` | Show the persistent top ten | Any player |
| `/wordchain wins <player>` | Show wins, current streak, and best streak | `wordchain.stats` |
| `/wordchain rewards` | Open reward editor | `wordchain.rewards` |
| `/wordchain reward add <material> <amount>` | Add/update a reward | `wordchain.rewards` |
| `/wordchain reward remove <material>` | Remove a reward | `wordchain.rewards` |
| `/wordchain difficulty <first> <last> <1-5>` | Tag phrase difficulty | `wordchain.admin` |
| `/wordchain duel <player> [seconds\|off]` | Challenge a player; `off` disables per-turn timeout | Any player |
| `/wordchain accept` / `/wordchain deny` | Accept or decline a pending duel | Any player |
| `/wordchain report <two syllables>` | Report a missing or disputed phrase | Any player, 30 second cooldown |
| `/wordchain add <two syllables>` | Add a phrase directly | `wordchain.admin` |
| `/wordchain remove <two syllables>` | Remove a phrase | `wordchain.admin` |
| `/wordchain reports` | List pending reports | `wordchain.admin` |
| `/wordchain approve <two syllables>` | Approve a report and add its phrase | `wordchain.admin` |
| `/wordchain reject <two syllables>` | Reject a report | `wordchain.admin` |
| `/wordchain import` | Import configured sources | `wordchain.admin` (default: operators) |
| `/wordchain reload` | Reload configuration | `wordchain.admin` |

Set `game.members-can-control: false` to restrict start/stop/reset to `wordchain.admin`. The menu actions use the same permission checks as commands.

## Configuration

- `game.target-moves`: valid linked phrases needed to win.
- `game.starting-word`: default starting phrase; it must exist in an imported list.
- `rewards.items`: list of `MATERIAL:amount` rewards, for example `DIAMOND:3`.
- `dictionary.sources`: source entries use `plain|URL`, `tab|URL`, or `kaikki|URL`. Plain lists use one phrase per line; tab lists use the first column; Kaikki JSONL imports Vietnamese headwords. `.gz` files are streamed through gzip decompression. Raw exports from the Hồ Ngọc Đức / `tudien` family and `tu-dien-tudientv` can use `plain` or `tab` as appropriate. Wiktionary can use the Kaikki extract shown in the example below.
- `dictionary.refresh-on-start`: import configured sources asynchronously on startup.
- `menu.title`: MiniMessage inventory title.
- `streak-rewards`: logarithmic win-streak multiplier and payout caps. The formula is `min(cap, 1 + rate * log2(streak))`.
- `anti-abuse.word-use-limit`: maximum uses of a phrase in a match, clamped to 1–5; the default is 3.
- `anti-abuse.max-hard-words-in-row` and `difficulty.hard-threshold`: stop long runs of admin-tagged difficult phrases. Set phrase levels with `/wordchain difficulty`.
- `duel.default-connect-seconds`: default turn deadline; players can override it with seconds or `off`. Every duel still stops after at most one hour.
- `game.next-match-delay-seconds`: delay before the next randomly selected keyword after a match.

The admin menu includes reward editing and a wins/streak browser. `wordchain.rewards` and `wordchain.stats` are separate operator permissions; `wordchain.admin` grants both. Other plugins can obtain the `com.example.wordchain.api.WordChainApi` service and listen for `WordChainMoveEvent` and `WordChainWinEvent`; database-backed API reads return a `CompletionStage`.

Streaks count consecutive match winners server-wide: a win advances that player's streak and resets other players' current streaks. Duel and public-game wins both count. Phrase difficulty defaults to level 1; admins can tag a phrase from 1 to 5, and the game limits consecutive phrases at or above `difficulty.hard-threshold`. Invalid two-syllable moves receive a private hint with a short per-player cooldown; longer chat messages are ignored.

The default source is the Viet39K phrase list from [duyet/vietnamese-wordlist](https://github.com/duyet/vietnamese-wordlist). Its README identifies the list as GPL-licensed; the plugin downloads it at runtime rather than bundling the word data. Check source licenses and attribution requirements before adding other lists or redistributing imported data.

The [Kaikki Vietnamese Wiktionary extract](https://kaikki.org/viwiktionary/rawdata.html) is a machine-readable Wiktionary dump. Wiktionary-derived material is provided under CC BY-SA / GFDL terms; keep attribution and share-alike obligations in mind when redistributing data.

Optional Kaikki/Wiktionary JSONL source (large; disabled by default):

```yaml
dictionary:
  sources:
    - "plain|https://raw.githubusercontent.com/duyet/vietnamese-wordlist/master/Viet39K.txt"
    - "kaikki|https://kaikki.org/viwiktionary/raw-wiktextract-data.jsonl.gz"
```

The importer has connection/read timeouts, skips oversized lines, caps a source at one million lines or 256 MB of decompressed text, and writes in SQLite batches. Chat messages are filtered to exactly two Vietnamese syllables before queueing game work. Menu clicks and drags are cancelled for plugin menus, and admin slots are permission checked.

## Notes

The chat event is asynchronous, so the plugin transfers move processing and all player/world changes to Paper's main thread. HTTP imports and leaderboard reads run asynchronously. The dictionary and wins are stored in `plugins/WordChain/wordchain.db`.
