# Miranda

> A news-driven forecaster that commits to testable predictions, keeps score, and corrects its own overconfidence.

Miranda reads headlines from RSS feeds, finds the dominant story, projects its coverage forward, issues dated and falsifiable predictions with probabilities, and writes a plain-English scenario of what may follow. Every later run grades the predictions that have fallen due, reports a track record, and recalibrates future probabilities accordingly.

Nobody can see the future, and Miranda doesn't claim to. What she can do is make claims you can check, and tell you honestly how well they have held up. See [Limitations](#limitations).

## Features

- Pulls and parses multiple RSS 2.0 feeds (BBC and Guardian by default, or your own)
- Identifies the dominant story by breadth of coverage, outlet count and headline prominence, merging near-duplicate terms into one story
- Measures **tone** (negative / mixed / positive), **momentum** and **domain** (conflict, economy, tech, politics, climate, health, general)
- **Trend forecast:** fits a line through the story's share of coverage over past runs and projects it 3 and 7 days ahead with an 80% range
- **Dated predictions:** each run logs four predictions about the dominant story, each with a probability
- **Self-grading:** predictions are graded automatically when they fall due
- **Track record:** hit rate and Brier score per prediction type, compared with a coin-flip baseline
- **Recalibration:** once a prediction type has 5+ graded results, its probabilities are shifted to correct systematic over- or under-confidence
- A three-stage narrative (days, weeks, months) plus an alternative branch
- Offline `--demo` mode
- Pure JDK: no Maven, no Gradle, no third-party libraries

## Requirements

- Java 16 or later
- An internet connection for live feeds (not needed for `--demo`)

## Quick start

```bash
git clone https://github.com/<your-username>/miranda.git
cd miranda

javac Miranda.java
java Miranda
```

Or, without a separate compile step:

```bash
java Miranda.java
```

**Run it daily.** Trend fitting needs observations on at least three different days, and predictions are graded 3 and 7 days after they are made. For example, with cron:

```
0 8 * * *  cd /path/to/miranda && java Miranda >> miranda.log 2>&1
```

## Usage

```bash
java Miranda                       # default feeds, state saved in ./miranda-data
java Miranda URL1 URL2 ...         # your own RSS feeds
java Miranda --demo                # offline synthetic headlines (state in ./miranda-demo-data)
java Miranda --data=DIR            # keep history and predictions in DIR
java Miranda --no-log              # analyse only; read and write nothing
java Miranda --asof=2026-10-01     # pretend today is this date (demos and backtests only)
java Miranda --help
```

### Try the learning loop offline

`--demo` with `--asof` lets you watch predictions being made and graded without waiting a week:

```bash
for d in 2026-10-01 2026-10-02 2026-10-03 2026-10-04; do
  java Miranda --demo --data=/tmp/miranda --asof=$d
done
```

The last run will grade the predictions made on 1 October.

## Example output

Abridged output from the fourth run above (synthetic headlines, not real news):

```
PREDICTIONS (logged and graded when due)
  - By 2026-10-07, "port strike" will still be among the top 5 stories.  -> 87%
  - By 2026-10-07, "port strike" will take a larger share of headlines than on 2026-10-04 (70%).  -> 50%
  - On 2026-10-07, coverage of "port strike" will still be clearly negative in tone.  -> 72%

GRADED TODAY
  - [CAME TRUE] By 2026-10-04, "port strike" will still be among the top 5 stories. (forecast 72%)
  - [DID NOT HAPPEN] By 2026-10-04, "port strike" will take a larger share of headlines
    than on 2026-10-01 (70%). (forecast 55%)

MIRANDA'S TRACK RECORD
  type             graded  came true  avg forecast  Brier (lower is better)
  SHARE_UP@3            1         0%           55%  0.303
  TONE_PERSIST@3        1       100%           72%  0.078
  TOP5@3                1       100%           72%  0.078
  Overall Brier score 0.153 over 3 graded predictions (a coin-flip forecaster scores 0.250).
```

## How it works

1. **Gather.** Feeds are downloaded and parsed with the JDK XML parser (DOCTYPE disabled to block XXE). HTML is stripped and duplicate headlines removed.
2. **Rank stories.** Words are tokenised, stopwords removed and plurals stemmed. Each term appearing in at least three articles scores `articles + 1.5 × (outlets − 1) + headline hits`. Terms whose articles overlap by more than 60% are merged into one story.
3. **Measure.** Tone is the average of (positive − negative lexicon words) per article. Momentum compares the last 8 hours of coverage with the preceding 24. Domain is the keyword lexicon with the most hits.
4. **Trend.** Each run saves the top 10 stories and their share of coverage. A least-squares line through the dominant story's share over the last 14 days (plus today) gives a projected share with a prediction interval. A story missing from a day's top 10 counts as a negligible share that day.
5. **Predict.** Four predictions are logged about the dominant story:

   | Type | Claim | Horizon |
   |---|---|---|
   | `TOP5` | It will still be among the top 5 stories | 3 and 7 days |
   | `SHARE_UP` | Its share of headlines will be higher than today | 3 days |
   | `TONE_PERSIST` | Its coverage will still be clearly negative (or positive) | 3 days |

   Initial probabilities come from simple priors (current momentum, how many consecutive days the story has been in the top 5, tone strength, or the fitted trend for `SHARE_UP`). These are educated guesses, which is why the next two steps matter.
6. **Grade.** On the first run on or after a prediction's due date (within a 2-day grace period), it is marked true or false. Predictions that cannot be graded (for example, the story vanished before tone could be measured) are voided and excluded.
7. **Calibrate.** For each prediction type with 5+ graded results, Miranda compares her average forecast with how often the claims actually came true and shifts future probabilities in logit space to close the gap, weighted by `n / (n + 10)`. The original probability is stored alongside the recalibrated one so the correction never compounds on itself.
8. **Narrate.** Hand-written causal templates for the chosen domain and tone are filled in for days, weeks and months, with the opposite tone offered as an alternative branch.

## Saved state

Two plain tab-separated files in the data directory (default `./miranda-data`):

| File | Columns |
|---|---|
| `history.tsv` | date, term, rank, article count, total articles, net sentiment |
| `predictions.tsv` | id, made, due, term, label, kind, param, raw probability, issued probability, outcome, resolved on |

Delete the directory to reset Miranda's memory. Re-running on the same day replaces that day's history rows and never duplicates predictions.

## Customising

Everything lives in `Miranda.java`:

| What to change | Where |
|---|---|
| Default feeds | `DEFAULT_FEEDS` |
| Trend look-back window | `HISTORY_DAYS` |
| Graded results needed before recalibrating | `MIN_FOR_CALIBRATION` |
| Grace period for grading | `GRACE_DAYS` |
| Stopwords, sentiment and domain vocabularies | `STOP`, `NEG`, `POS`, `LEX` |
| Initial probability priors and new prediction types | `makePredictions`, `resolve`, `statement` |
| Narrative templates | `chain(Domain)` |

To add a prediction type, create it in `makePredictions`, grade it in `resolve`, and describe it in `statement`. Tracking and calibration pick it up automatically.

## Limitations

- **It cannot foresee events.** The predictions are about news coverage (what will be in the headlines, how big it will be, what tone it will have), not about the underlying events. The narrative is template-based and will sound plausible whatever the facts. Do not use any of it for financial, political or safety decisions.
- **Early probabilities are guesses.** Until a prediction type has 5+ graded results it is uncalibrated, and with only a handful of results the track record is statistically noisy.
- **A cold start takes time.** No trend appears until three different days have been recorded, and the first grades arrive three days after the first run.
- **Story identity can drift.** A story is tracked by its top-ranked term; if the leading word changes from day to day, history for that story may be split.
- **Sentiment is crude.** A small word list cannot handle negation or sarcasm.
- **Coverage is not reality.** Feeds are editorial selections, and the result reflects what these outlets chose to publish.
- **Only RSS 2.0 `<item>` feeds are supported; English only.**

## Roadmap ideas

- Replace the template narrative with an LLM call that takes the clustered headlines and track record as input
- Named-entity extraction instead of word frequency, for more stable story identity
- Predictions for the other top stories, not just the dominant one
- Atom feed support
- Unit tests for scoring, grading and calibration
- A reliability diagram built from `predictions.tsv`

## A note on feeds

Check the terms of use of any feed you point Miranda at. She sends a descriptive `User-Agent` and makes one request per feed per run. Please don't run her in a tight loop.

## Contributing

Issues and pull requests are welcome. If you change priors, lexicons or templates, include a short before/after example in the PR description.

## License

Released under the MIT License. See `LICENSE` for details.
