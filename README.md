# Cassandra

> A news-driven forecaster that commits to testable predictions, keeps score, and corrects its own overconfidence.

Cassandra reads headlines from RSS feeds, finds the leading stories, projects their coverage forward, issues dated and falsifiable predictions with probabilities, and writes a plain-English scenario of what may follow. Every later run grades the predictions that have fallen due, reports a track record, and recalibrates future probabilities accordingly.

Nobody can see the future, and Cassandra doesn't claim to. What she can do is make claims you can check, and tell you honestly how well they have held up. See [How accurate is she?](#how-accurate-is-she) and [Limitations](#limitations).

![Cassandra's desktop window, showing the Today tab: three story cards with predictions, a coverage chart and headlines](docs/today-tab.png)

*The Today tab of the desktop window (`--gui`), after 35 days of synthetic demo data. The [Accuracy tab](#desktop-window) shows how well her predictions have held up.*

## How accurate is she?

**On real news: not yet known.** No live track record has been published yet. A fair verdict needs at least a few weeks of daily runs, and this section will be updated with the real `--report` output once there is one. Until then, treat every probability she gives as unproven.

What *can* be said is how she is judged, and that the judging is built to be hard to pass.

### How she is scored

Every prediction is logged with the date it was made, its due date and its probability, then graded automatically when it falls due (see [How it works](#how-it-works)). `java Cassandra --report` scores the whole record against three opponents:

| Opponent | What it does | Why it matters |
|---|---|---|
| **Coin flip** | Always says 50% | The bare minimum. Easy to beat, because many claims (such as "the leading story will still be in the top 5") are usually true. |
| **Base-rate forecaster** | For each prediction, says how often that type of claim had come true so far, using only results known on the day | **The opponent that matters.** Beating it means Cassandra's reading of the news adds something beyond "what usually happens". |
| **Herself, before recalibration** | Her raw probabilities | Shows whether the self-correction actually helps. |

Scores use the **Brier score**: the average squared gap between the probability given and what happened (1 if it came true, 0 if not). 0 is perfect, and always saying 50% scores 0.250.

The headline number is the **skill score**, `1 − Brier(Cassandra) / Brier(base rate)`. Above 0, she beats the base rate; 0 means no better; below 0 means worse.

To stop the report flattering her:

- **No verdict until there is evidence.** The base-rate forecaster needs 5 earlier results of a type before it makes a forecast, and the report gives no verdict until 30 predictions can be compared.
- **An honest margin of error.** The 90% interval on the skill score is bootstrapped by resampling *whole days*, because predictions made on the same day are about the same stories and tend to succeed or fail together.
- **Three possible verdicts:** she beats the base rate (the whole interval is above 0), she is worse (the whole interval is below 0), or she cannot yet be told apart from it.
- **Where she goes wrong.** A reliability table shows, for each band of forecasts (0–20%, 20–40% …), how often those claims actually came true.

### What the report looks like

Abridged output after 35 days of `--demo` runs (synthetic headlines, not real news):

```
THE SHORT ANSWER
  Cassandra is doing WORSE than simply forecasting how often each type of claim usually
  comes true. Treat her probabilities with suspicion.
  Skill score -0.17 over 237 comparable predictions (90% interval -0.28 to -0.08). 0 means
  no better than the base rate, 1 would be perfect, below 0 is worse.

BRIER SCORES (lower is better; 0 is perfect)
  All 294 graded predictions:
    Cassandra (issued probabilities)    0.178
    Cassandra before recalibration      0.203
    Coin flip (always 50%)              0.250
  The 237 predictions the base-rate forecaster could also make:
    Cassandra                           0.179
    Base-rate forecaster                0.153

BY PREDICTION TYPE
  type             graded  came true  avg forecast   Brier   skill vs base rate
  SHARE_UP@3           80        36%           42%   0.290   -0.28 (on 68)
  TONE_PERSIST@3       62        95%           87%   0.067   -0.05 (on 52)
  TOP5@3               80        85%           84%   0.147   -0.09 (on 68)
  TOP5@7               72        83%           77%   0.184   -0.10 (on 49)

RELIABILITY (when she says X%, does it happen X% of the time?)
  forecast  graded  avg forecast  came true  verdict
  0-20%         11           10%        45%  forecasts too low by 35 pts
  20-40%        20           33%        50%  forecasts too low by 17 pts
  40-60%        54           49%        46%  well calibrated
  60-80%        69           72%        81%  well calibrated
  80-100%      140           90%        86%  well calibrated

BRIER SCORE BY WEEK (week starting, by date graded)
  week of      graded  Cassandra  before recal  Cassandra vs base rate
  2026-10-05      61      0.153         0.160  0.171 vs 0.136 (on 23)
  2026-10-12      69      0.209         0.230  0.202 vs 0.157 (on 55)
  2026-10-19      70      0.150         0.188  0.150 vs 0.133 (on 70)
  2026-10-26      61      0.217         0.238  0.217 vs 0.191 (on 61)
```

How to read this demo result:

- **She beats a coin flip easily** (0.178 against 0.250), which would look impressive on its own.
- **She loses to the base rate**, and the interval says this is not bad luck. That is the right answer for the demo: its headlines are random draws from a fixed set, so there is nothing to read beyond how often each kind of claim usually comes true. The report is not rigged in her favour.
- **`SHARE_UP` is her weakest prediction** (skill −0.28). Day-to-day swings in a story's share of coverage are hard to call.
- **Her low forecasts are too low.** Claims she rated under 20% likely came true 45% of the time.
- **Recalibration helps** (0.203 → 0.178), but not enough to close the gap.

### Producing a real track record

```bash
# run daily, e.g. from cron, for four weeks or more
java Cassandra

# then
java Cassandra --report
```

`--report` only reads `predictions.tsv`; it fetches nothing and changes nothing, so you can run it as often as you like. `java Cassandra --gui` shows the same results as charts; see [Desktop window](#desktop-window).

## What's new

### Today tab

The desktop window now opens on a **Today** tab showing what the latest run found. See [Desktop window](#desktop-window) for a tour.

- **A card for each of the top 3 stories**: share of headlines, article count, tone and momentum, with a "new today" badge for stories that have just appeared
- **Each story's predictions** with a probability bar, and their outcome once graded (pending, came true, didn't happen or void). Hover a prediction for its full wording, or a bar to see whether recalibration changed it.
- **Coverage chart** for the selected story: its daily share of headlines since it first appeared, the fitted trend, and the projection 7 days ahead with its 80% range
- **Latest headlines** for the selected story. Click one to open the article in your browser.
- **The scenario narrative** for the top story, clearly labelled as an ungraded template rather than a prediction, plus the other storylines to watch
- **A warning when the latest run is 2 or more days old**, since missed runs void predictions
- **Click a card** to switch the chart and headlines to that story

To make this possible without the window fetching or writing anything:

- **Each run saves a snapshot** of what it found to `latest-run.tsv` (see [Saved state](#saved-state)). It is replaced on every run and written safely, like the other files.
- **Article links are kept.** Cassandra now reads each feed item's `<link>`, so headlines can be opened. Demo headlines have no links.

Terminal output, `history.tsv` and `predictions.tsv` are unchanged; checked against 35 days of demo runs.

### Desktop window (`--gui`)

A Swing window that shows Cassandra's accuracy as charts instead of text, on its **Accuracy** tab:

- **Verdict banner** in green, red, amber or grey: beating the base rate, worse than it, no clear edge, or too early to judge
- **Headline figures**: skill against the base rate with its interval, Brier score, what a coin flip would score, and what recalibration has gained
- **Reliability diagram**: forecast probability against how often it came true, with a 90% range for each band
- **Brier score by week** for Cassandra, Cassandra before recalibration and the base-rate forecaster, with the coin-flip line for reference
- **Per-type table**, with skill shown in colour once a type has enough comparable predictions
- **Hover** over a dot or a week for its exact figures
- **Reloads by itself** when a scheduled run saves new results while the window is open
- **Read-only**: it never writes to the data folder, and offers no way to change past outcomes

### Under the hood

- **The engine is separate from the printing.** A run now produces a result object (`forecastRun` → `RunResult`), and so does the accuracy report (`computeAccuracy` → `Accuracy`). The terminal printers and the GUI only read these. Terminal output and saved files are byte-for-byte unchanged; this was checked against 35 days of demo runs.
- **Safe saves.** `history.tsv` and `predictions.tsv` are written to a temporary file and then renamed into place, so anything reading them, such as the window, never sees a half-written file.

### Earlier in this release

- **Accuracy report (`--report`).** Skill score against a base-rate forecaster with a 90% interval and a plain-English verdict, Brier scores against three benchmarks, a per-type breakdown, a reliability table and weekly Brier scores. The end-of-run track record also gives the skill score. See [How accurate is she?](#how-accurate-is-she).
- **Predictions for the top 3 stories.** Each of the top 3 stories gets its own predictions, so about three times as many are graded each day and recalibration starts within the first week. Lower-ranked stories start with a lower `TOP5` probability (`RANK_PENALTY`).
- **Fair trends for new stories.** The trend is fitted only from the day a story first appeared in the top 10, instead of counting the days before as 0% coverage. New stories are labelled "new today", and the forecast explains when there is not yet enough data for a trend.
- **Livelier demo.** `--demo` headlines change from day to day: a fading port strike, a steady AI chip story, and a storm that breaks on 3 October and grows.
- **Tidier output.** Wrapped prediction lines have a hanging indent.

Existing `history.tsv` and `predictions.tsv` files work unchanged.

## Features

- Pulls and parses multiple RSS 2.0 feeds (BBC and Guardian by default, or your own)
- Identifies the leading stories by breadth of coverage, outlet count and headline prominence, merging near-duplicate terms into one story
- Measures **tone** (negative / mixed / positive), **momentum** and **domain** (conflict, economy, tech, politics, climate, health, general)
- **Trend forecast:** fits a line through the dominant story's share of coverage since it first appeared and projects it 3 and 7 days ahead with an 80% range
- **Dated predictions:** up to four predictions for each of the top 3 stories every run, each with a probability
- **Self-grading:** predictions are graded automatically when they fall due
- **Track record:** hit rate and Brier score per prediction type, compared with a coin flip and a base-rate forecaster
- **Accuracy report (`--report`):** skill score with an interval, reliability table and weekly Brier scores
- **Desktop window (`--gui`):** today's stories, predictions, coverage charts and clickable headlines, plus the accuracy report as charts; reloads by itself after each scheduled run
- **Recalibration:** once a prediction type has 5+ graded results, its probabilities are shifted to correct systematic over- or under-confidence
- A three-stage narrative (days, weeks, months) plus an alternative branch for the dominant story
- Offline `--demo` mode with a multi-day storyline
- Pure JDK: no Maven, no Gradle, no third-party libraries

## Requirements

- Java 16 or later
- An internet connection for live feeds (not needed for `--demo`, `--report` or `--gui`)
- A desktop session for `--gui` (everything else runs headless, e.g. from cron)

## Quick start

```bash
git clone https://github.com/<your-username>/Cassandra.git
cd Cassandra

javac Cassandra.java
java Cassandra
```

Or, without a separate compile step:

```bash
java Cassandra.java
```

**Run it daily.** Trend fitting needs observations on at least three different days, and predictions are graded 3 and 7 days after they are made. For example, with cron:

```
0 8 * * *  cd /path/to/cassandra && java Cassandra >> cassandra.log 2>&1
```

## Usage

```bash
java Cassandra                       # default feeds, state saved in ./cassandra-data
java Cassandra URL1 URL2 ...         # your own RSS feeds
java Cassandra --demo                # offline synthetic headlines (state in ./cassandra-demo-data)
java Cassandra --data=DIR            # keep history and predictions in DIR
java Cassandra --no-log              # analyse only; read and write nothing
java Cassandra --asof=2026-10-01     # pretend today is this date (demos and backtests only)
java Cassandra --report              # accuracy report from saved predictions; fetches nothing
java Cassandra --report --demo       # accuracy report for the demo data directory
java Cassandra --gui                 # desktop window for ./cassandra-data
java Cassandra --gui --data=DIR      # desktop window for another data directory
java Cassandra --help
```

### Try the learning loop offline

`--demo` with `--asof` lets you watch predictions being made and graded without waiting a week:

```bash
for d in 2026-10-01 2026-10-02 2026-10-03 2026-10-04; do
  java Cassandra --demo --data=/tmp/cassandra --asof=$d
done
```

On 3 October the storm story appears and is marked "new today", with no trend fitted. The 4 October run grades the predictions made on 1 October.

To reproduce the accuracy report shown above, run 35 days and then report:

```bash
for i in $(seq 0 34); do
  java Cassandra --demo --data=/tmp/cassandra --asof=$(date -d "2026-10-01 +$i day" +%F) > /dev/null
done
java Cassandra --report --data=/tmp/cassandra
```

(On macOS, use `date -v+${i}d -j -f %F 2026-10-01 +%F` in place of the `date -d` call.) Then open the same data in the window with `java Cassandra --gui --data=/tmp/cassandra`.

## Desktop window

```bash
java Cassandra --gui                  # or: java Cassandra.java --gui
```

The window opens on `./cassandra-data` (or the folder given with `--data=`, or `./cassandra-demo-data` with `--demo`). **Choose…** switches to another data folder and **Reload** re-reads it, though it also reloads by itself within a few seconds whenever a run saves new results. It only reads the folder, so it is safe to leave open while cron runs Cassandra. **Help → About Cassandra** (on a Mac, also **Cassandra → About Cassandra**) shows the version, licence and the project's GitHub page.

### Today tab

What the latest run found (see the screenshot at the top of this page). It reads `latest-run.tsv`, so it shows the most recent saved run, not live news.

| Part | What it shows | How to read it |
|---|---|---|
| Header | The date of the latest run and how many headlines it read | An amber warning appears if that run is 2 or more days old: check your scheduled job. |
| Story cards | The top 3 stories, each with its share of headlines, tone, momentum and predictions | The bar is her probability. The status on the right turns into "came true" or "didn't happen" once the prediction is graded. Click a card to select it. |
| Coverage chart | The selected story's daily share of headlines since it first appeared, the fitted trend, and the next 7 days | Dots are what happened. The dashed line and shaded band are the projection and its 80% range; the marked points are the 3- and 7-day forecasts. A new or young story has no trend yet. |
| Latest headlines | Up to 5 recent headlines about the selected story | Blue, underlined headlines open in your browser. |
| What may follow | The template scenario for the top story, and the other storylines to watch | Not a prediction and not graded: it reads plausibly whatever happens. |

![The Accuracy tab: verdict banner, headline figures, reliability diagram, weekly Brier scores and per-type table](docs/accuracy-tab.png)

### Accuracy tab

From top to bottom:

| Part | What it shows | How to read it |
|---|---|---|
| Verdict banner | The same verdict as `--report`, with the skill score and its 90% interval | Green: beating the base rate. Red: worse. Amber: no clear edge. Grey: too early to judge. |
| Headline figures | Skill vs base rate, Brier score, what a coin flip would score, the effect of recalibration | Skill above 0 is good; Brier closer to 0 is good. A provisional skill score is shown in grey. |
| Reliability diagram | One dot per band of forecasts: average forecast (across) against how often those claims came true (up) | On the diagonal = well calibrated. Above it = things happen more often than she says; below = less often. Bars show a 90% range for the true rate; big dots hold more predictions; hollow dots have fewer than 5. |
| Brier score by week | Cassandra, Cassandra before recalibration, and the base-rate forecaster, week by week | Lower is better. Blue below grey = beating the base rate that week. Dashed above solid blue = recalibration helped. |
| Per-type table | Each prediction type's hit rate, average forecast, Brier score and skill | Skill turns green or red only once a type has 10 comparable predictions; below that it is grey, because it is mostly noise. |

Hover over a dot or a week for its exact numbers. The window uses your system's look, so on a Mac it will look slightly different from the screenshot.

## Example output

Abridged output from the fourth run of the four-day loop above (synthetic headlines, not real news):

```
COVERAGE FORECAST (share of headlines mentioning this story)
  Trend over 4 days of data (since 2026-10-01): -3.8 percentage points per day. Today 44%.
  In 3 days: about 27% (80% range 7%-47%)
  In 7 days: about 12% (80% range 0%-46%)

PREDICTIONS FOR THE TOP 2 STORIES (logged and graded when due)
  1. "port strike" - 44% of coverage
     - By 2026-10-07, "port strike" will still be among the top 5 stories.  -> 87%
     - By 2026-10-11, "port strike" will still be among the top 5 stories.  -> 72%
     - By 2026-10-07, "port strike" will take a larger share of headlines than on
       2026-10-04 (44%).  -> 23%
     - On 2026-10-07, coverage of "port strike" will still be clearly negative in tone.
       -> 72%
  2. "storm" - 25% of coverage
     - By 2026-10-07, "storm" will still be among the top 5 stories.  -> 70%
     - By 2026-10-11, "storm" will still be among the top 5 stories.  -> 55%
     - By 2026-10-07, "storm" will take a larger share of headlines than on 2026-10-04
       (25%).  -> 55%

GRADED TODAY
  - [CAME TRUE] By 2026-10-04, "port strike" will still be among the top 5 stories.
  (forecast 72%)
  - [DID NOT HAPPEN] By 2026-10-04, "port strike" will take a larger share of headlines
  than on 2026-10-01 (53%). (forecast 55%)
  - [DID NOT HAPPEN] By 2026-10-04, "ai chip" will still be among the top 5 stories.
  (forecast 65%)

CASSANDRA'S TRACK RECORD
  type             graded  came true  avg forecast  Brier (lower is better)
  SHARE_UP@3            2         0%           55%  0.303
  TONE_PERSIST@3        1       100%           72%  0.078
  TOP5@3                2        50%           69%  0.250
  Overall Brier score 0.237 over 5 graded predictions (a coin-flip forecaster scores
  0.250; a perfect one 0.000). Cassandra is currently beating a coin flip. Pending: 29.
  Too early to compare her with a base-rate forecaster (0 of 30 comparable predictions).
  Run with --report for details.
```

"Top 2" rather than 3 here because only two stories had enough coverage that day.

## How it works

1. **Gather.** Feeds are downloaded and parsed with the JDK XML parser (DOCTYPE disabled to block XXE). HTML and feed boilerplate ("Continue reading…") are stripped and duplicate headlines removed.
2. **Rank stories.** Words are tokenised, stopwords removed and plurals stemmed. Each term appearing in at least three articles scores `articles + 1.5 × (outlets − 1) + headline hits`. Terms whose articles overlap by more than 60% are merged into one story.
3. **Measure.** For each of the top 3 stories: tone is the average of (positive − negative lexicon words) per article, and momentum compares the last 8 hours of coverage with the preceding 24. For the dominant story, the domain is the keyword lexicon with the most hits.
4. **Trend.** Each run saves the top 10 stories and their share of coverage. For each leading story, a least-squares line is fitted through its share from the first day it appeared in the top 10 (within the last 14 days) up to today, giving a projected share with a prediction interval. Days before it appeared are ignored; a day it drops out of the top 10 after appearing counts as a negligible share. At least 3 days of data are needed.
5. **Predict.** Up to four predictions are logged about each of the top 3 stories:

   | Type | Claim | Horizon |
   |---|---|---|
   | `TOP5` | It will still be among the top 5 stories | 3 and 7 days |
   | `SHARE_UP` | Its share of headlines will be higher than today | 3 days |
   | `TONE_PERSIST` | Its coverage will still be clearly negative (or positive); skipped when tone is mixed | 3 days |

   Initial probabilities come from simple priors: the story's rank, its momentum, how many consecutive days it has been in the top 5, tone strength, or its fitted trend for `SHARE_UP` (momentum if there is no trend yet). These are educated guesses, which is why the next steps matter.
6. **Grade.** On the first run on or after a prediction's due date (within a 2-day grace period), it is marked true or false. Predictions that cannot be graded (for example, the story vanished before tone could be measured, or no run happened within the grace period) are voided and excluded from all scores.
7. **Calibrate.** For each prediction type with 5+ graded results, Cassandra compares her average forecast with how often the claims actually came true and shifts future probabilities in logit space to close the gap, weighted by `n / (n + 10)`. Results from all three stories are pooled per type. The original probability is stored alongside the recalibrated one so the correction never compounds on itself.
8. **Score.** Each run's track record gives Brier scores per type and the skill score against the base-rate forecaster. `--report` adds the interval, verdict, reliability table and weekly scores described in [How accurate is she?](#how-accurate-is-she).
9. **Narrate.** Hand-written causal templates for the dominant story's domain and tone are filled in for days, weeks and months, with the opposite tone offered as an alternative branch.

## Saved state

Three plain tab-separated files in the data directory (default `./cassandra-data`):

| File | Columns |
|---|---|
| `history.tsv` | date, term, rank, article count, total articles, net sentiment |
| `predictions.tsv` | id, made, due, term, label, kind, param, raw probability, issued probability, outcome, resolved on |
| `latest-run.tsv` | A snapshot of the latest run for the window's Today tab: one tagged line per item (`run`, `story`, `headline` with its link, `prediction`, `step`, `alternative`, `other`). Replaced on every run. |

`predictions.tsv` is the complete track record: every prediction ever made, with its probability and outcome. It is plain text, so anyone can check the scores independently. `latest-run.tsv` is only for display; deleting it loses nothing that matters, and the next run writes a new one.

Delete the directory to reset Cassandra's memory. Re-running on the same day replaces that day's history rows and never duplicates predictions.

## Customising

Everything lives in `Cassandra.java`:

| What to change | Where |
|---|---|
| Default feeds | `DEFAULT_FEEDS` |
| Number of stories that get predictions | `PREDICTED_STORIES` |
| How much lower the `TOP5` prior starts for each rank below #1 | `RANK_PENALTY` |
| Trend look-back window | `HISTORY_DAYS` |
| Graded results needed before recalibrating | `MIN_FOR_CALIBRATION` |
| Grace period for grading | `GRACE_DAYS` |
| Earlier results the base-rate forecaster needs | `BASE_WARMUP` |
| Comparable predictions needed before `--report` gives a verdict | `MIN_FOR_VERDICT` |
| Bootstrap resamples for the skill-score interval | `BOOTSTRAP` |
| Comparable predictions before a type's skill is coloured in the window | `Gui.MIN_TYPE_SKILL` |
| The desktop window, its tabs and charts | the `Gui` class at the end of the file |
| Stopwords, sentiment and domain vocabularies | `STOP`, `NEG`, `POS`, `LEX` |
| Initial probability priors and new prediction types | `makePredictions`, `resolve`, `statement` |
| Narrative templates | `chain(Domain)` |
| Demo headlines and storyline | `DEMO_PORT`, `DEMO_CHIP`, `DEMO_STORM`, `DEMO_FILLER`, `demoArticles` |

To add a prediction type, create it in `makePredictions`, grade it in `resolve`, and describe it in `statement`. Tracking, calibration, the accuracy report and the window pick it up automatically, for every leading story (give it readable wording in `Gui.describeType` and `Gui.shortStatement`).

The code is split so that calculating and showing never mix: `forecastRun` returns a `RunResult` and `computeAccuracy` returns an `Accuracy`; `printRun`, `printAccuracy` and the `Gui` only read those. A new view, such as JSON output, needs only a new reader.

## Limitations

- **It cannot foresee events.** The predictions are about news coverage (what will be in the headlines, how big it will be, what tone it will have), not about the underlying events. The narrative is template-based and will sound plausible whatever the facts. Do not use any of it for financial, political or safety decisions.
- **No live track record yet.** The only accuracy figures so far come from synthetic demo data.
- **Early probabilities are guesses.** Until a prediction type has 5+ graded results it is uncalibrated, and with only a handful of results the track record is statistically noisy.
- **The interval is approximate.** Resampling by day allows for predictions on the same day being linked, but not for links across days (the same story is predicted about on several days running), so the true uncertainty is somewhat wider.
- **Calibration is pooled across ranks.** The rank penalty is a fixed guess; if Cassandra is well calibrated for the leading story but not for the third, the pooled correction will only partly fix it.
- **A cold start takes time.** No trend appears until three different days have been recorded, the first grades arrive three days after the first run, and a verdict needs a few weeks.
- **Story identity can drift.** A story is tracked by its top-ranked term; if the leading word changes from day to day, history for that story may be split, and it may be treated as new.
- **Sentiment is crude.** A small word list cannot handle negation or sarcasm.
- **Coverage is not reality.** Feeds are editorial selections, and the result reflects what these outlets chose to publish.
- **Only RSS 2.0 `<item>` feeds are supported; English only.**

## Roadmap

Planned features, roughly in priority order. None of these are implemented yet.

### Better learning

- **Fix the low-probability bias.** The demo report shows claims rated under 20% coming true far more often than forecast; the `SHARE_UP` prior is the main suspect.
- **New prediction types.** For example `DROP_OUT` (the story leaves the top 10 within 7 days) and `OUTLETS_UP` (more outlets covering it in 3 days).
- **HTML accuracy report.** The window's charts as a self-contained HTML page, for sharing the track record.

### Desktop window

- **Predictions ledger.** Every prediction in a sortable, filterable, read-only table, with CSV export.
- **Story history.** A story's share and rank over time, with the predictions made about it marked hit or miss.
- **Run controls.** Run now (in the background, with a live log), a demo toggle, a "Simulate N days" button for the demo, and a list of any days the daily run was missed.
- **Feeds and settings.** Add, remove and test feeds, and adjust thresholds, saved to a config file.

### Backtesting

- **Headline archive (`--archive`).** Save each run's raw headlines to `archive/YYYY-MM-DD.tsv`.
- **Replay mode (`--replay=DIR`).** Re-run the whole pipeline over archived days in order, so changes to priors, lexicons or scoring can be tested against weeks of real data in seconds instead of waiting for new grades.

### Smarter analysis

- **Stable story identity.** Match today's stories to yesterday's by overlap of their top terms, not by a single leading word, and give each story a persistent ID in `history.tsv`.
- **Robust timestamps.** Leave articles with unparseable dates out of the momentum calculation, instead of treating them as published "now".
- **Negation-aware sentiment.** Flip the polarity of a lexicon word preceded within two words by "not", "no", "never", "without" and similar.
- **Bigram story labels** used as the tracked key, so "port strike" and "general strike" are not the same story.

### Practicalities

- **Atom feed support** (`<entry>` elements) alongside RSS 2.0.
- **Polite fetching.** Send `If-Modified-Since` / `ETag` headers and skip unchanged feeds.
- **Config file (`cassandra.properties`)** for feeds, thresholds and data directory, so nothing needs recompiling.
- **Machine-readable output (`--json`)** for dashboards or other tools.
- **Unit tests** for scoring, grading, calibration and the trend fit, runnable with plain `java` and no test framework.
- **LLM narrative (optional).** Replace the template narrative with a model call that receives the clustered headlines and Cassandra's track record, while keeping the templates as an offline fallback.

## A note on feeds

Check the terms of use of any feed you point Cassandra at. She sends a descriptive `User-Agent` and makes one request per feed per run. Please don't run her in a tight loop.

## Contributing

Issues and pull requests are welcome. If you change priors, lexicons or templates, include a short before/after example in the PR description, ideally with `--report` output from the same data before and after.

## License

Released under the MIT License. See `LICENSE` for details.
