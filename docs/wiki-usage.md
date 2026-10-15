# OSRS Wiki usage

What OSRS Companion loads from the Old School RuneScape Wiki, how often, and how much data that is.

## The plugin

The plugin loads only two kinds of picture from the wiki:
- **The current roadmap goal's picture**, in the roadmap box and on the side panel's Roadmap page. A roadmap shows one goal at a time (the first one not done yet), so at most **one** picture until that goal is reached; then the next goal's. Item and skill goals use RuneLite's own icons and load nothing from the wiki.
- **The active boss setup's picture** on the side panel's Boss gear page: one, until you load another setup.

Each picture is loaded once, then kept in memory (at most 512) until RuneLite closes or the plugin is turned off. Only `https://oldschool.runescape.wiki/images/...` files are loaded; any other address is ignored. A picture that fails is not asked again until the next start, and at most 16 load at once. Wiki links (the boss gear's **Go to wiki**, the slayer guide) open in your browser only when you click them.

## The website

The website also asks the wiki for data, from its server. Every request names the site and the contact `Discord: Zeekr` in its User-Agent, as the wiki asks.

The website's server code runs on Vercel, which starts a copy of it (a *server instance*) when a request comes in and none is running, keeps it for as long as requests keep coming, and stops it after some minutes without any. When it is busy, more copies run side by side. What a copy remembers (the lists below) is gone when it stops, so a newly started copy loads it again. "Per server instance" therefore means: once by each copy that needs it, while that copy runs.

- **Item names and icons**: the prices API's item list (`prices.runescape.wiki/api/v1/osrs/mapping`), at most once a day per server instance.
- **GE prices**: the latest prices (`prices.runescape.wiki/api/v1/osrs/latest`), at most every 10 minutes per server instance.
- **Untradeable items** (capes, pets, quest items) the website's own item list doesn't have: their name and picture from the wiki's item data (`api.php?action=bucket`), 50 items per request, once per item per server instance.
- **Monster pictures** whose plain image doesn't exist (monsters with several versions): the wiki's monster data (`api.php`), once per monster per server instance.
- **Collection log via WikiSync** (`sync.runescape.wiki`): the items the wiki's WikiSync plugin knows you have, at most once every 30 minutes per player (across all server instances).
- **Images on the website**: fetched from the wiki once, then kept a year by Vercel's cache and your browser (a missing image: a day).
- **Game data** (quest and diary requirements, wiki boss gear presets, drop rates, slayer, combat and farming data): copied from the wiki by the developer with scripts when the game changes (at most about 3 requests a second), stored in the website's code. Never while you play.

## Estimate per player per day

Measured sizes (October 2026): a monster picture 60-120 KB, a quest, unlock or achievement icon under 1 KB, a WikiSync answer about 10 KB, the item list 140 KB and the price list 75 KB (compressed).

| What | Typical day | Most |
|---|---|---|
| Plugin pictures (goal + boss setup, loaded again each time RuneLite starts) | 2 starts: 0-250 KB | about 0.5 MB (a monster goal and boss setup, a few starts, a goal reached) |
| WikiSync | a few answers: 10-50 KB | 48 answers: about 0.5 MB |
| **Per player** | **under 0.3 MB** | **about 1 MB** |

Item names, prices, untradeable items, monster pictures and website images are shared by everyone and do not grow with players: with one server instance running all day, about 75 KB × 144 price loads + 140 KB item list ≈ **11 MB a day for the whole website** at most, usually less (the prices load only when someone's bank, loot or gear is shown or synced).
