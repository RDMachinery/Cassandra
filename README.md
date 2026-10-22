# Cassandra

> A news-driven forecaster that commits to testable predictions, keeps score, and corrects its own overconfidence.

Cassandra reads headlines from RSS feeds, finds the leading stories, projects their coverage forward, issues dated and falsifiable predictions with probabilities, and writes a plain-English scenario of what may follow. Every later run grades the predictions that have fallen due, reports a track record, and recalibrates future probabilities accordingly.

Nobody can see the future, and Cassandra doesn't claim to. What she can do is make claims you can check, and tell you honestly how well they have held up. See [Limitations](#limitations).

## What's new

### Predictions for the top 3 stories

Cassandra used to make predictions only about the single dominant story. She now makes them for each of the **top 3 stories** of the day, grouped by story in the output.

- **Faster learning.** About three times as many predictions are graded each day. In a 12-day demo run, 78 predictions were graded. All three 3-day prediction types had passed the 5-result threshold for recalibration by day 6, and the 7-day type by day 10.
- **Rank-aware priors.** A story in third place is less secure in the top 5 than the leader, so its starting `TOP5` probability is lowered by 7 percentage points per rank (`RANK_PENALTY`).
- **One shared track record.** All stories feed the same per-type statistics, so calibration learns from every prediction, whichever story it was about.
- The number of stories is set by `PREDICTED_STORIES`.

### Fair trends for new stories

Previously, the days before a story existed counted as 0% coverage, so every new story looked like a steep riser and its `SHARE_UP` forecast was inflated exactly when the story was most likely to fade.

- The trend is now fitted **only from the day a story first appeared** in the top 10. A day it drops out *after* appearing still counts as a negligible share, because that drop is real.
- New stories are labelled **"new today"** in the predictions list.
- The coverage forecast explains why there is no trend yet: no history at all, a **new story**, or a **young story** with fewer than 3 days of data. Until a trend is available, `SHARE_UP` falls back on today's momentum.
- The trend line now says when the data starts, e.g. `Trend over 4 days of data (since 2026-10-03)`.

### Smaller improvements

- **Livelier demo.** `--demo` headlines now change from day to day: a port strike that slowly fades, a steady AI chip story, and a storm that breaks on 3 October and grows. The mix is random but reproducible (the same date always gives the same headlines).
- **Tidier output.** Wrapped prediction lines now have a hanging indent.

Existing `history.tsv` and `predictions.tsv` files work unchanged.

## Features

- Pulls and parses multiple RSS 2.0 feeds (BBC and Guardian by default, or your own)
- Identifies the leading stories by breadth of coverage, outlet count and headline prominence, merging near-duplicate terms into one story
- Measures **tone** (negative / mixed / positive), **momentum** and **domain** (conflict, economy, tech, politics, climate, health, general)
- **Trend forecast:** fits a line through the dominant story's share of coverage since it first appeared and projects it 3 and 7 days ahead with an 80% range
- **Dated predictions:** up to four predictions for each of the top 3 stories every run, each with a probability
- **Self-grading:** predictions are graded automatically when they fall due
- **Track record:** hit rate and Brier score per prediction type, compared with a coin-flip baseline
- **Recalibration:** once a prediction type has 5+ graded results, its probabilities are shifted to correct systematic over- or under-confidence
- A three-stage narrative (days, weeks, months) plus an alternative branch for the dominant story
- Offline `--demo` mode with a multi-day storyline
- Pure JDK: no Maven, no Gradle, no third-party libraries

## Requirements

- Java 16 or later
- An internet connection for live feeds (not needed for `--demo`)

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
java Cassandra --help
```

### Try the learning loop offline

`--demo` with `--asof` lets you watch predictions being made and graded without waiting a week:

```bash
for d in 2026-10-01 2026-10-02 2026-10-03 2026-10-04; do
  java Cassandra --demo --data=/tmp/cassandra --asof=$d
done
```

On 3 October the storm story appears and is marked "new today", with no trend fitted. The 4 October run grades the predictions made on 1 October. Carry the loop on to 12 October to see recalibration kick in.

## Example output

Abridged output from the fourth run above (synthetic headlines, not real news):

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

   Initial probabilities come from simple priors: the story's rank, its momentum, how many consecutive days it has been in the top 5, tone strength, or its fitted trend for `SHARE_UP` (momentum if there is no trend yet). These are educated guesses, which is why the next two steps matter.
6. **Grade.** On the first run on or after a prediction's due date (within a 2-day grace period), it is marked true or false. Predictions that cannot be graded (for example, the story vanished before tone could be measured, or no run happened within the grace period) are voided and excluded.
7. **Calibrate.** For each prediction type with 5+ graded results, Cassandra compares her average forecast with how often the claims actually came true and shifts future probabilities in logit space to close the gap, weighted by `n / (n + 10)`. Results from all three stories are pooled per type. The original probability is stored alongside the recalibrated one so the correction never compounds on itself.
8. **Narrate.** Hand-written causal templates for the dominant story's domain and tone are filled in for days, weeks and months, with the opposite tone offered as an alternative branch.

## Saved state

Two plain tab-separated files in the data directory (default `./cassandra-data`):

| File | Columns |
|---|---|
| `history.tsv` | date, term, rank, article count, total articles, net sentiment |
| `predictions.tsv` | id, made, due, term, label, kind, param, raw probability, issued probability, outcome, resolved on |

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
| Stopwords, sentiment and domain vocabularies | `STOP`, `NEG`, `POS`, `LEX` |
| Initial probability priors and new prediction types | `makePredictions`, `resolve`, `statement` |
| Narrative templates | `chain(Domain)` |
| Demo headlines and storyline | `DEMO_PORT`, `DEMO_CHIP`, `DEMO_STORM`, `DEMO_FILLER`, `demoArticles` |

To add a prediction type, create it in `makePredictions`, grade it in `resolve`, and describe it in `statement`. Tracking and calibration pick it up automatically, for every leading story.

## Limitations

- **It cannot foresee events.** The predictions are about news coverage (what will be in the headlines, how big it will be, what tone it will have), not about the underlying events. The narrative is template-based and will sound plausible whatever the facts. Do not use any of it for financial, political or safety decisions.
- **Early probabilities are guesses.** Until a prediction type has 5+ graded results it is uncalibrated, and with only a handful of results the track record is statistically noisy.
- **Calibration is pooled across ranks.** The rank penalty is a fixed guess; if Cassandra is well calibrated for the leading story but not for the third, the pooled correction will only partly fix it.
- **A cold start takes time.** No trend appears until three different days have been recorded, and the first grades arrive three days after the first run.
- **Story identity can drift.** A story is tracked by its top-ranked term; if the leading word changes from day to day, history for that story may be split, and it may be treated as new.
- **Sentiment is crude.** A small word list cannot handle negation or sarcasm.
- **Coverage is not reality.** Feeds are editorial selections, and the result reflects what these outlets chose to publish.
- **Only RSS 2.0 `<item>` feeds are supported; English only.**

## Roadmap

Planned features, roughly in priority order. None of these are implemented yet.

### Better learning

- **Stronger baselines.** Report a Brier *skill score* against each type's historical base rate (for example, "stories in the top 5 stay there 70% of the time"), not only against a coin flip, which is an easy bar to clear.
- **Calibration report (`--report`).** A reliability table built from `predictions.tsv`: forecasts grouped into bins (0–20%, 20–40% …) against how often they came true, plus a Brier score trend over time. Optionally written as an HTML chart.
- **New prediction types.** For example `DROP_OUT` (the story leaves the top 10 within 7 days) and `OUTLETS_UP` (more outlets covering it in 3 days).

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

Issues and pull requests are welcome. If you change priors, lexicons or templates, include a short before/after example in the PR description.

## License

Released under the MIT License. See `LICENSE` for details.
