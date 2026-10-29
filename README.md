# WordChain (Paper)

MIT licensed. See [LICENSE](LICENSE).

A Vietnamese word-chain minigame for Paper 26.2+ and Java 25. It imports UTF-8 word lists, Kaikki JSONL, and the `NhanAZ-Drops/tu-dien-tieng-viet` release archive in the background, stores normalized entries in SQLite, and indexes phrases by first syllable. Wins and leaderboard totals persist across restarts.

## Build and install

Run `mvn package` with Java 25. Copy `target/word-chain-1.2.1.jar` into the server's `plugins` folder and restart. Edit `plugins/WordChain/config.yml`, then run `/wordchain reload`. The bundled SQLite driver supports Linux x86_64/aarch64 (glibc and musl), Windows x86_64, and macOS x86_64/aarch64 servers.

## Commands and permissions

| Command | Description | Permission |
| --- | --- | --- |
| `/wordchain` or `/wc` | Open the game menu | Any player |
| `/wordchain start [two-word phrase]` | Start with a random viable phrase, or the specified phrase | `wordchain.play` (default: everyone) |
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

### LuckPerms setup

Basic player permissions are available to everyone by default. To assign them explicitly to LuckPerms' default group, run:

```text
/lp group default permission set wordchain.player true
```

`wordchain.player` includes `wordchain.play`, `wordchain.menu`, `wordchain.top`, `wordchain.duel`, `wordchain.duel.respond`, and `wordchain.report`. Remove a specific permission from the default group to restrict that command, for example `/lp group default permission set wordchain.duel false`. Admin permissions remain separate: `wordchain.admin`, `wordchain.rewards`, and `wordchain.stats`.

## Configuration

- `game.target-moves`: valid linked phrases needed to win.
- `game.recent-start-limit`: number of recent random start phrases to avoid repeating.
- `rewards.items`: list of `MATERIAL:amount` rewards, for example `DIAMOND:3`.
- `dictionary.sources`: source entries use `plain|URL`, `tab|URL`, `kaikki|URL`, or `tu-dien-archive|URL`. The NhanAZ release archive streams through the importer; only `data/processed/words/*.json` is parsed and only exactly two-syllable headwords are indexed. The 124 MB archive is not stored on disk.
- `dictionary.refresh-on-start`: check configured sources asynchronously on startup. Imported source URLs are recorded in SQLite and skipped on later starts; `/wordchain import` forces a refresh.
- `menu.title`: MiniMessage inventory title.
- `streak-rewards`: logarithmic win-streak multiplier and payout caps. The formula is `min(cap, 1 + rate * log2(streak))`.
- `anti-abuse.word-use-limit`: maximum uses of a phrase in a match, clamped to 1–5; the default is 3.
- `anti-abuse.max-hard-words-in-row` and `difficulty.hard-threshold`: stop long runs of admin-tagged difficult phrases. Set phrase levels with `/wordchain difficulty`.
- `duel.default-connect-seconds`: default turn deadline; players can override it with seconds or `off`. Every duel still stops after at most one hour.
- `game.next-match-delay-seconds`: delay before the next randomly selected keyword after a match.

The admin menu includes reward editing and a wins/streak browser. `wordchain.rewards` and `wordchain.stats` are separate operator permissions; `wordchain.admin` grants both. Other plugins can obtain the `com.example.wordchain.api.WordChainApi` service and listen for `WordChainMoveEvent` and `WordChainWinEvent`; database-backed API reads return a `CompletionStage`.

Public-game streaks count consecutive server-wide match winners. Duel wins advance the winner's streak and reset only the opponent's streak. Phrase difficulty defaults to level 1; admins can tag a phrase from 1 to 5. If a difficulty rule or used-word limit leaves no legal continuation, the round resets with a fresh random keyword instead of stalling. Starts avoid recent keywords where possible. Invalid two-syllable moves receive a private hint with a short per-player cooldown; longer chat messages are ignored.

The default sources are the Viet39K phrase list and the [NhanAZ-Drops Vietnamese dictionary release](https://github.com/NhanAZ-Drops/tu-dien-tieng-viet/releases/tag/v0.4.0-beta). The upstream release reports 89,569 dictionary headwords and contains mixed per-source terms (MIT, GPL, CC BY-SA/GFDL, public domain, and some sources marked license-unclear). The plugin downloads the archive at runtime and does not bundle or republish the data. Review the upstream [data card](https://github.com/NhanAZ-Drops/tu-dien-tieng-viet/blob/main/reports/current/DATA_CARD.md) before redistributing imported database contents.

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
