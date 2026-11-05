# Cassandra: technical guide

The detail behind the [README](../README.md): how Cassandra is scored, how a run works, the files she saves, every setting, the command-line options, replay, publishing and the version history.

## Contents

1. [How she is scored](#how-she-is-scored)
2. [How a run works](#how-a-run-works)
3. [Command-line reference](#command-line-reference)
4. [Saved state](#saved-state)
5. [Settings and customising](#settings-and-customising)
6. [The desktop window in detail](#the-desktop-window-in-detail)
7. [Replay](#replay)
8. [Publishing on GitHub Pages](#publishing-on-github-pages)
9. [Known limitations](#known-limitations)
10. [Roadmap](#roadmap)
11. [Contributing](#contributing)
12. [Version history](#version-history)

## How she is scored

Every prediction is logged with the date it was made, its due date and its probability, then graded automatically when it falls due (see [How a run works](#how-a-run-works)). `java Cassandra --report` scores the whole record against three opponents:

| Opponent | What it does | Why it matters |
|---|---|---|
| **Coin flip** | Always says 50% | The bare minimum. Easy to beat, because many claims (such as "the leading story will still be in the top 5") are usually true. |
| **Base-rate forecaster** | For each prediction, says how often that type of claim had come true so far, using only results known on the day | **The opponent that matters.** Beating it means Cassandra's reading of the news adds something beyond "what usually happens". |
| **Herself, before recalibration** | Her hand-set first guesses, before the base rate and the evidence are applied | Shows whether learning from her record actually helps. |

Scores use the **Brier score**: the average squared gap between the probability given and what happened (1 if it came true, 0 if not). 0 is perfect, and always saying 50% scores 0.250.

The headline number is the **skill score**, `1 − Brier(Cassandra) / Brier(base rate)`. Above 0, she beats the base rate; 0 means no better; below 0 means worse.

To stop the report flattering her:

- **No verdict until there is evidence.** The base-rate forecaster needs 5 earlier results of a type before it makes a forecast, and the report gives no verdict until 30 predictions can be compared.
- **An honest margin of error.** The 90% interval on the skill score is bootstrapped by resampling *whole days*, because predictions made on the same day are about the same stories and tend to succeed or fail together.
- **Three possible verdicts:** she beats the base rate (the whole interval is above 0), she is worse (the whole interval is below 0), or she cannot yet be told apart from it.
- **Where she goes wrong.** A reliability table shows, for each band of forecasts (0–20%, 20–40% …), how often those claims actually came true. A gap of more than 10 points is flagged; bands with fewer than 5 results are marked "too few to judge".
- **Which method the score reflects.** A record holds predictions made under every version she has run. The report splits the comparable predictions by how each probability was set (her hand-set first guess alone, the 2.0 base-rate method, the 2.1 blend, or 2.3's evidence weighed by its record), so an old method can't hide how she is doing now, or the other way round.
- **Whether the evidence helps.** For each type, the Brier score of where she started (`q=`) against where the evidence would have taken her (`e=`), and the say the evidence has earned as of today.


### What the report looks like

Abridged output after 35 days of `--demo` runs (synthetic headlines, not real news; see [Try the learning loop offline](#try-the-learning-loop-offline) to reproduce it):

```
THE SHORT ANSWER
  Cassandra is doing WORSE than simply forecasting how often each type of claim usually
  comes true. Treat her probabilities with suspicion.
  Skill score -0.01 over 249 comparable predictions (90% interval -0.03 to -0.00). 0 means
  no better than the base rate, 1 would be perfect, below 0 is worse.

BRIER SCORES (lower is better; 0 is perfect)
  All 307 graded predictions:
    Cassandra (issued probabilities)    0.183
    Cassandra before recalibration      0.193
    Coin flip (always 50%)              0.250
  The 249 predictions the base-rate forecaster could also make:
    Cassandra                           0.184
    Base-rate forecaster                0.181

BY PREDICTION TYPE
  type             graded  came true  avg forecast   Brier   skill vs base rate
  SHARE_UP@3           80        38%           35%   0.245   -0.01 (on 68)
  TONE_HOLDS@3         75        80%           83%   0.174   -0.01 (on 64)
  TOP5@3               80        85%           85%   0.142   -0.01 (on 68)
  TOP5@7               72        83%           83%   0.169   -0.02 (on 49)

BY HOW THE PROBABILITY WAS SET (comparable predictions only)
  method                                          count  Cassandra  base rate  skill
  Evidence weighed by its record (2.3)              249      0.184      0.181  -0.01

HAS THE EVIDENCE EARNED ITS SAY? (predictions made since 2.3)
  type             graded  Brier without  Brier with  weight now
  SHARE_UP@3           73          0.256       0.265          0%
  TONE_HOLDS@3         68          0.176       0.178         11%
  TOP5@3               73          0.138       0.139         22%
  TOP5@7               55          0.171       0.167         73%

RELIABILITY (when she says X%, does it happen X% of the time?)
  forecast  graded  avg forecast  came true  verdict
  0-20%          9           15%        44%  forecasts too low by 29 pts
  20-40%        55           36%        38%  well calibrated
  40-60%        25           46%        52%  well calibrated
  60-80%        39           71%        92%  forecasts too low by 21 pts
  80-100%      179           88%        80%  well calibrated

BRIER SCORE BY WEEK (week starting, by date graded)
  week of      graded  Cassandra  before recal  Cassandra vs base rate
  2026-09-28       6      0.178         0.178  n/a
  2026-10-05      62      0.168         0.161  0.168 vs 0.169 (on 24)
  2026-10-12      72      0.198         0.210  0.196 vs 0.190 (on 58)
  2026-10-19      73      0.154         0.185  0.154 vs 0.153 (on 73)
  2026-10-26      65      0.224         0.220  0.224 vs 0.222 (on 65)
  2026-11-02      29      0.155         0.181  0.155 vs 0.150 (on 29)
```

How to read this demo result:

- **She is level with the base rate, give or take a whisker.** The verdict says "worse" because the interval (−0.03 to −0.00) just excludes zero, but the gap is 0.003 on the Brier score. That is the right kind of answer for the demo: its headlines are random draws following fixed storylines, so there is little to read beyond how often each kind of claim usually comes true. The report is not rigged in her favour.
- **The evidence has earned little say.** On the demo, rank, momentum and trend barely help, and for `SHARE_UP` they hurt, so they have 0–22% of a say on three types and 73% on `TOP5@7`.
- **The tone claim is harder than it was.** Under 2.2 the demo's tone predictions came true 95% of the time; as `TONE_HOLDS`, which counts a fading story as a miss, 80%. The difference is the misses the old rule voided.
- **Version 2.0 was measurably worse on the same 35 days** (skill −0.04, interval −0.08 to −0.01), on easier tone claims.
- **Some numbers rest on few results.** The 0–20% band's 29-point gap is from 9 predictions; treat it as noise until there are many more.

#### On a simulated news world

The demo has only a handful of fixed storylines, so the forecasting changes were also tested on three simulated 90-day news worlds: stories arriving at random, rising for a day or two, then fading at very different speeds, with tone that belongs to the story and drifts slowly. Each world's headlines were replayed through each version:

| Overall Brier score | World 1 | World 2 | World 3 |
|---|---|---|---|
| First 20 days, 2.0 | 0.201 | 0.206 | 0.234 |
| First 20 days, 2.1 | 0.203 | 0.192 | 0.225 |
| First 20 days, 2.2 | 0.187 | 0.199 | 0.212 |
| 90 days, 2.0 | 0.177 | 0.184 | 0.194 |
| 90 days, 2.1 | 0.178 | 0.182 | 0.194 |
| 90 days, 2.2 | 0.170 | 0.179 | 0.177 |

2.1's gain is in the first weeks, while her first guesses still carry weight. 2.2's comes from story detection: in these worlds, tone words such as "attack" appear across many stories, and 2.0 and 2.1 sometimes treated them as stories of their own. After 90 days 2.2 beats the base rate in all three worlds (skill +0.12, +0.09 and +0.13).

2.3's overall scores aren't comparable with these, because its tone claim is harder (see [Version 2.3](#version-23)). Type by type, making the evidence earn its say costs a little in these worlds, where the evidence genuinely helps: after 90 days `TOP5@3` scores 0.187, 0.192 and 0.189 against 2.2's 0.183, 0.190 and 0.186, because the evidence starts with no say and its weight is held back. 2.3 still beats the base rate in all three worlds (skill +0.11, +0.06 and +0.10). On real headlines, where the evidence has misled her, the trade goes the other way (see [Version 2.3](#version-23)).

A simulated world is only a rough stand-in for real headlines. The real test is replaying a real archive (see [Replay](#replay)).

## How a run works

1. **Gather.** Feeds are downloaded and parsed with the JDK XML parser (DOCTYPE disabled to block XXE). HTML and feed boilerplate ("Continue reading…") are stripped and duplicate headlines removed.
2. **Rank stories.** Newsletter and app promotions are removed (`PROMOS`), "n't" is read as "not", and words are tokenised: stopwords removed (`STOP`, plus `STOP2` for story detection only), any word containing a digit dropped (so "$6.3bn" gives nothing), and plurals stemmed. Each term appearing in at least three articles scores `articles + 1.5 × (outlets − 1) + headline hits`, except words that can't name a story on their own: generic words (`GENERIC`: "government", "state", "time" …) and tone words (`NEG`, `POS`). These still count towards labels, tone and related terms. Terms whose articles overlap by more than 60% are merged into one story.
3. **Measure.** For each of the top 3 stories: tone is the average of (positive − negative lexicon words) per article, and momentum compares today's share of headlines with the story's average over the previous 3 days of runs (accelerating or fading needs a change of at least 3 points and 30%; a story absent from those runs is new). For the dominant story, the domain is the keyword lexicon with the most hits.
4. **Trend.** Each run saves the top 10 stories and their share of coverage. For each leading story, a least-squares line is fitted through its share from the first day it appeared in the top 10 (within the last 14 days) up to today, giving a projected share with a prediction interval. Days before it appeared are ignored; a day it drops out of the top 10 after appearing counts as a negligible share. At least 3 days of data are needed.
5. **Predict.** Up to four predictions are logged about each of the top 3 stories:

   | Type | Claim | Horizon | Evidence recorded |
   |---|---|---|---|
   | `TOP5` | It will still be among the top 5 stories | 3 and 7 days | rank (`r=`), momentum (`m=`), days already in the top 5 (`k=`: 0, 1-2 or 3+) |
   | `SHARE_UP` | Its share of headlines will be higher than today | 3 days | trend direction (`t=`: up, flat, down or none), momentum, rank |
   | `TONE_HOLDS` | It will still be in the news (3+ headlines), with coverage still clearly negative (or positive); skipped when tone is mixed | 3 days | tone strength (`s=`: mild, medium or strong), rank |

   Until 2.2 the tone claim was `TONE_PERSIST`, "coverage will still be clearly negative", without "still in the news"; see step 6 for why it was replaced.

   Each probability is worked out in four steps:

   - **Her first guess.** A hand-set rule: for `TOP5@3`, 62% for the leading story, lowered by the rank penalty for each place below first, plus 5 points per day already in the top 5 (up to 5 days), plus 10 if coverage is accelerating or minus 15 if it is fading; `TOP5@7` is 5 points lower. For `SHARE_UP`, 12% plus half the probability that the fitted trend is rising (so 12–62%), or with no trend 40% if accelerating, 30% if new, 15% if fading and 25% otherwise. For `TONE_HOLDS`, 68%, 75% or 82% for mild, medium or strong tone, times the chance it is still in the news (92%, less 4 points per place below first). These lean towards *things staying as they are*: a story is chosen for being among today's biggest, so its share today is usually near its peak.
   - **The guess, corrected for her bias.** If her guesses of this type have averaged, say, 50% on claims that came true 25% of the time, the guess is shifted in logit space to close the gap, damped by `n / (n + 10)`. Only guesses made under the current rules count (the `g=` evidence field; see `GUESS_RULES`), because the bias of older rules says nothing about the new ones.
   - **Blended with the record: the start.** Once the type has any graded results, she starts from `q = (hits + w × guess) / (results + w)`, where `w` is the `calibration.min` setting (default 5): her guess counts as 5 results, so after 5 results the record weighs as much as the guess, and after 30 it outweighs it six to one.
   - **What the evidence says, and how much say it has earned.** She refines the start through each piece of evidence in turn: the rate among earlier predictions that shared it, blended with the previous step as `(hits + 10 × previous) / (results + 10)`. That gives the evidence-based probability `e`. She then issues `q + v × (e − q)`, where `v` is the weight the evidence has earned for this type (`evidenceWeight`). Every prediction records its `q=` and `e=`, both made before the outcome was known. Over the type's graded predictions, the weight that would have scored best is found by least squares, `v* = Σ (e − q)(y − q) / Σ (e − q)²` with `y` = 1 if it came true, kept between 0 and 1, then shrunk by `n / (n + 20)` (`EVIDENCE_PRIOR`). So the evidence has no say until her own record shows it helps; if it has pointed the wrong way, `v*` is 0 and she sticks with the start. Only results already known on the day are used.

   Probabilities are kept between 3% and 97%. The output shows where each came from, e.g. `-> 69% [base rate 50% over 2 results and first guess 77% give 69%; the evidence (71%) has not yet earned a say]`.
6. **Grade.** On the first run on or after a prediction's due date (within a 2-day grace period), it is marked true or false, using the story-detection rules it was made under: predictions without `d=2` in their evidence are graded with the 2.1 rules, so changing how stories are found never rescores claims already made. A `TONE_HOLDS` claim whose story has fewer than 3 headlines on the day has faded from the news, and counts as a miss. Predictions that cannot be graded are voided and excluded from all scores: those with no run within the grace period, and `TONE_PERSIST` claims (made before 2.3) whose story had fewer than 3 headlines. That old rule hid misses: a story that fades is the likeliest to fail a tone claim, and voiding it left it out of the scores.
7. **Keep the first guess.** Her first guess is stored alongside the probability she issued, so the report can show what learning from her record has gained ("before recalibration").
8. **Score.** Each run's track record gives Brier scores per type and the skill score against the base-rate forecaster. `--report` adds the interval, verdict, reliability table and weekly scores described in [How she is scored](#how-she-is-scored).
9. **Narrate.** Hand-written causal templates for the dominant story's domain and tone are filled in for days, weeks and months, with the opposite tone offered as an alternative branch.

### Example output

Abridged output from the fourth run of the four-day loop in [Try the learning loop offline](#try-the-learning-loop-offline) (synthetic headlines, not real news):

```
COVERAGE FORECAST (share of headlines mentioning this story)
  Trend over 4 days of data (since 2026-10-01): -3.8 percentage points per day. Today 44%.
  In 3 days: about 27% (80% range 7%-47%)
  In 7 days: about 12% (80% range 0%-46%)

PREDICTIONS FOR THE TOP 2 STORIES (logged and graded when due)
  1. "port strike" - 44% of coverage
     - By 2026-10-07, "port strike" will still be among the top 5 stories.  -> 69% [base
       rate 50% over 2 results and first guess 77% give 69%; the evidence (71%) has not
       yet earned a say]
     - By 2026-10-11, "port strike" will still be among the top 5 stories.  -> 72%
     - By 2026-10-07, "port strike" will take a larger share of headlines than on
       2026-10-04 (44%).  -> 16% [base rate 17% over 2 results and first guess 24% give
       16%; the evidence (16%) has not yet earned a say]
     - On 2026-10-07, "port strike" will still be in the news, with coverage still clearly
       negative in tone.  -> 66% [base rate 50% over 2 results and first guess 75% give
       66%; the evidence (72%) has not yet earned a say]
  ...

GRADED TODAY
  - [CAME TRUE] By 2026-10-04, "port strike" will still be among the top 5 stories.
  (forecast 62%)
  - [DID NOT HAPPEN] By 2026-10-04, "port strike" will take a larger share of headlines
  than on 2026-10-01 (53%). (forecast 25%)
  - [CAME TRUE] On 2026-10-04, "port strike" will still be in the news, with coverage
  still clearly negative in tone. (forecast 75%)
  - [DID NOT HAPPEN] On 2026-10-04, "ai chip" will still be in the news, with coverage
  still clearly positive in tone. (forecast 66%)
  ...

CASSANDRA'S TRACK RECORD
  type             graded  came true  avg forecast  Brier (lower is better)
  SHARE_UP@3            2         0%           25%  0.063
  TONE_HOLDS@3          2        50%           71%  0.248
  TOP5@3                2        50%           59%  0.223
  Overall Brier score 0.178 over 6 graded predictions (a coin-flip forecaster scores
  0.250; a perfect one 0.000). Cassandra is currently beating a coin flip. Pending: 29.
  Too early to compare her with a base-rate forecaster (0 of 30 comparable predictions).
  Run with --report for details.
```

The first 3-day predictions have just been graded, so the 3-day types already lean a little on their record; `TOP5@7` has no results yet, so that line is her first guess alone. With so few results the guess still counts for most: after 2 results it weighs 5 to 2. The evidence has no record yet, so it has no say. Note the "ai chip" tone claim: the story faded from the news, which under `TONE_PERSIST` would have been voided and left out of the score; under `TONE_HOLDS` it is a miss.

## Command-line reference


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
java Cassandra --gui --demo          # desktop window in demo mode
java Cassandra --replay=DIR          # replay DIR's archived headlines into DIR-replay
java Cassandra --replay=DIR --data=OUT   # ... into OUT, with OUT's cassandra.properties if any
java Cassandra --publish=SITE        # run, then write a static website of her forecasts into SITE
java Cassandra --publish-only=SITE   # write the website from saved results, without running
java Cassandra --publish=SITE --site-url=URL   # URL is the site's address, used in the RSS feed
java Cassandra --selftest            # run the built-in checks offline; exit status 0 if all pass
java Cassandra --help
```

**Requirements:** Java 16 or later; an internet connection for live feeds (not needed for `--demo`, `--report` or `--gui`); a desktop session for `--gui` (everything else runs headless, e.g. from cron). Pure JDK: no Maven, no Gradle, no third-party libraries.

### Try the learning loop offline

`--demo` with `--asof` lets you watch predictions being made and graded without waiting a week:

```bash
for d in 2026-10-01 2026-10-02 2026-10-03 2026-10-04; do
  java Cassandra --demo --data=/tmp/cassandra --asof=$d
done
```

On 3 October the storm story appears and is marked "new today", with no trend fitted. The 4 October run grades the predictions made on 1 October.

To reproduce the accuracy report shown in [What the report looks like](#what-the-report-looks-like), run 35 days and then report:

```bash
for i in $(seq 0 34); do
  java Cassandra --demo --data=/tmp/cassandra --asof=$(date -d "2026-10-01 +$i day" +%F) > /dev/null
done
java Cassandra --report --data=/tmp/cassandra
```

(On macOS, use `date -v+${i}d -j -f %F 2026-10-01 +%F` in place of the `date -d` call.) Then open the same data in the window with `java Cassandra --gui --data=/tmp/cassandra`.

## Saved state

Plain-text files in the data directory (default `./cassandra-data`):

| File | Contents |
|---|---|
| `history.tsv` | date, term, rank, article count, total articles, net sentiment |
| `predictions.tsv` | id, made, due, term, label, kind, param, first guess, issued probability, outcome, resolved on, evidence (e.g. `r=2;m=steady;k=3+;g=2;d=2;b=0.8500;n=32;q=0.8400;e=0.7900;w=0.35`: rank, momentum, days already in the top 5, the edition of the first-guess rules, the edition of the story-detection rules, the type's base rate, the number of results it rested on, where she started, where the evidence would have taken her, and the say the evidence had earned; `k=`, `g=` and `n=` were added in 2.1, `d=` in 2.2 and `q=`, `e=` and `w=` in 2.3, and the column is empty for predictions made before 26 October 2026) |
| `latest-run.tsv` | A snapshot of the latest run for the window's Today tab: one tagged line per item (`run`, `story`, `headline` with its link, `prediction`, `step`, `alternative`, `other`). Replaced on every run. |
| `cassandra.properties` | Optional settings for this folder: `feed.1`, `feed.2` … and `min.articles`, `predicted.stories`, `rank.penalty`, `history.days`, `calibration.min`, `grace.days`. Written by the Settings tab, with a comment on each line; you can also edit it by hand. Missing keys use the defaults. |
| `cassandra.lock` | An empty file that runs lock while saving, so two runs can't overwrite each other. Safe to ignore. |
| `archive/YYYY-MM-DD.tsv` | The headlines each saving run analysed: a `#ran` line with the run's time, then one line per headline (source, published, title, summary, link). Used by replay. Roughly tens of kilobytes a day with the default feeds. |

`predictions.tsv` is the complete track record: every prediction ever made, with its probability and outcome. It is plain text, so anyone can check the scores independently. `latest-run.tsv` is only for display; deleting it loses nothing that matters, and the next run writes a new one.

Delete the directory to reset Cassandra's memory. Re-running on the same day replaces that day's history rows and never duplicates predictions.

## Settings and customising

The feeds and the main thresholds can be changed without touching the code, per data folder, on the window's **Settings** tab or in `cassandra.properties` (see [Saved state](#saved-state)):

| Setting | Key in `cassandra.properties` | Default | Range |
|---|---|---|---|
| News feeds | `feed.1`, `feed.2`, … | BBC and Guardian world, business and technology | any RSS 2.0 feed |
| Headlines needed for a run | `min.articles` | 8 | 3–50 |
| Stories that get predictions | `predicted.stories` | 3 | 1–5 |
| How much lower the `TOP5` prior starts for each rank below #1 | `rank.penalty` | 0.07 | 0–0.2 |
| Trend look-back window (days) | `history.days` | 14 | 3–60 |
| How many graded results her first guess is worth (after this many, a type's record counts as much as her guess) | `calibration.min` | 5 | 2–50 |
| Grace period for grading (days) | `grace.days` | 2 | 0–7 |

Everything else lives in `Cassandra.java`:

| What to change | Where |
|---|---|
| Default settings (when a folder has no `cassandra.properties`) | `Settings.defaults()`, `DEFAULT_FEEDS` |
| Earlier results the base-rate forecaster needs before it forecasts | `BASE_WARMUP` |
| How strongly a group of predictions is pulled towards its parent's rate | `BASE_PRIOR_WEIGHT` |
| How many results the evidence needs before its own record counts half towards its say (default 20; 10 made little difference in testing) | `EVIDENCE_PRIOR` |
| Days of runs that momentum compares today with | `MOMENTUM_DAYS` |
| The evidence each prediction type uses | `makePredictions` |
| Comparable predictions needed before `--report` gives a verdict | `MIN_FOR_VERDICT` |
| Bootstrap resamples for the skill-score interval | `BOOTSTRAP` |
| Comparable predictions before a type's skill is coloured in the window | `Gui.MIN_TYPE_SKILL` |
| The desktop window, its tabs and charts | the `Gui` class at the end of the file |
| Stopwords, sentiment and domain vocabularies | `STOP`, `NEG`, `POS`, `LEX` |
| Words left out of story detection, words that can't be a story on their own, and newsletter promotions to strip (bump `STORY_RULES` whenever you change these or `tokenize`) | `STOP2`, `GENERIC`, `PROMOS`, `STORY_RULES` |
| First-guess priors and new prediction types (bump `GUESS_RULES` whenever you change the priors) | `makePredictions`, `resolve`, `statement`, `GUESS_RULES` |
| Narrative templates | `chain(Domain)` |
| Demo headlines and storyline | `DEMO_PORT`, `DEMO_CHIP`, `DEMO_STORM`, `DEMO_FILLER`, `demoArticles` |

To add a prediction type, create it in `makePredictions`, grade it in `resolve`, and describe it in `statement`. Tracking, calibration, the accuracy report and the window pick it up automatically, for every leading story (give it readable wording in `Gui.describeType` and `Gui.shortStatement`).

The code is split so that calculating and showing never mix: `forecastRun` returns a `RunResult` and `computeAccuracy` returns an `Accuracy`; `printRun`, `printAccuracy` and the `Gui` only read those. A new view, such as JSON output, needs only a new reader.

## The desktop window in detail

The window opens on `./cassandra-data` (or the folder given with `--data=`, or `./cassandra-demo-data` with `--demo`). **Choose…** switches to another data folder and **Reload** re-reads it, though it also reloads by itself within a few seconds whenever a run saves new results. The window only writes to the folder when you press a run button on the Run tab or **Save** on the Settings tab; otherwise it just reads, so it is safe to leave open while cron runs Cassandra. **Help → About Cassandra** (on a Mac, also **Cassandra → About Cassandra**) shows the version, licence and the project's GitHub page.

#### Today tab

What the latest run found (see the screenshot at the top of the README). It reads `latest-run.tsv`, so it shows the most recent saved run, not live news.

| Part | What it shows | How to read it |
|---|---|---|
| Header | The date of the latest run and how many headlines it read | An amber warning appears if that run is 2 or more days old: check your scheduled job. |
| Story cards | The top 3 stories, each with its share of headlines, tone, momentum and predictions | The bar is her probability; hover it to see where it came from. The status on the right turns into "came true" or "didn't happen" once the prediction is graded. Click a card to select it; double-click to open its Story history. |
| Coverage chart | The selected story's daily share of headlines since it first appeared, the fitted trend, and the next 7 days | Dots are what happened. The dashed line and shaded band are the projection and its 80% range; the marked points are the 3- and 7-day forecasts. A new or young story has no trend yet. |
| Latest headlines | Up to 5 recent headlines about the selected story | Blue, underlined headlines open in your browser. |
| What may follow | The template scenario for the top story, and the other storylines to watch | Not a prediction and not graded: it reads plausibly whatever happens. |

![The Accuracy tab: verdict banner, headline figures, reliability diagram, weekly Brier scores and per-type table](accuracy-tab-3.png)

#### Accuracy tab

From top to bottom:

| Part | What it shows | How to read it |
|---|---|---|
| Verdict banner | The same verdict as `--report`, with the skill score and its 90% interval | Green: beating the base rate. Red: worse. Amber: no clear edge. Grey: too early to judge. |
| Headline figures | Skill vs base rate, Brier score, what a coin flip would score, the effect of recalibration | Skill above 0 is good; Brier closer to 0 is good. A provisional skill score is shown in grey. |
| Reliability diagram | One dot per band of forecasts: average forecast (across) against how often those claims came true (up) | On the diagonal = well calibrated. Above it = things happen more often than she says; below = less often. Bars show a 90% range for the true rate; big dots hold more predictions; hollow dots have fewer than 5. |
| Brier score by week | Cassandra, Cassandra before recalibration, and the base-rate forecaster, week by week | Lower is better. Blue below grey = beating the base rate that week. Dashed above solid blue = recalibration helped. |
| Per-type table | Each prediction type's hit rate, average forecast, Brier score and skill | Skill turns green or red only once a type has 10 comparable predictions; below that it is grey, because it is mostly noise. |

Hover over a dot or a week for its exact numbers.

#### Predictions tab

![The Predictions tab, filtered to one prediction type, showing pending and graded predictions](predictions-tab.png)

Every prediction in `predictions.tsv`, as a table. The screenshot is filtered to one type, "Still in the top 5 after 7 days", and sorted by due date.

| Part | What it does |
|---|---|
| Filters | **Show** picks an outcome and a prediction type; **Search** matches the story, the wording or the type. **Clear filters** resets them. |
| Summary | For the rows shown: how many, how many have been graded, how many of those came true against her average forecast, and their Brier score. |
| Table | Click a heading to sort, shift-click a second heading to sort by both. Hover a row for the prediction in full. "Before recal." is grey where recalibration made no difference. |
| Export CSV… | Saves the rows shown, in the order shown, as a CSV file with the id, dates, story, type, short claim, full statement, both probabilities, outcome and grading date. |

The summary is a quick way to answer questions such as "how does she do on the storm story?" (search for it) or "does she over-rate tone predictions?" (pick that type). With few rows, treat its figures as rough.

#### Story history tab

![The Story history tab for the storm story: share of headlines, rank and prediction outcomes on one timeline](story-history-tab.png)

One story's whole life. Pick it from the list on the left (type in **Find a story** to narrow it); the screenshot shows the demo's storm story.

| Part | What it shows | How to read it |
|---|---|---|
| Story list | Every story that has been in the top 10: runs spent there (**Days**), highest rank (**Best**), last seen, predictions made | Sort by any column. Only the top 3 stories each day get predictions, so some stories have none. |
| Summary | Its spell in the top 10, its best rank, and how her predictions about it fared | Hit rate against average forecast, and the Brier score, for this story alone. |
| Share of headlines | Its share of each run's headlines | Dots are runs where it was in the top 10, joined when those runs were consecutive. Ticks on the axis are runs where it was outside the top 10, where its share was not recorded. |
| Rank | Its position among the day's stories, #1 at the top | The shaded band is the top 5, which the `TOP5` predictions are about. |
| Predictions | Every prediction about it, one lane per type, at its due date | Green dot: came true. Red cross: didn't happen. Hollow circle: pending. Grey dash: void. The grey area to the right of "latest run" is the future, where everything is still pending. |

Hover any dot or marker for details. **Show its predictions** opens the Predictions tab filtered to this story.

Reading the bands together shows how her predictions relate to what happened. In the screenshot, the red crosses in the "Share up" lane tend to cluster a few days after the storm's coverage peaks: she expected it to keep growing, and it didn't.

#### Run tab

![The Run tab in demo mode after simulating 35 days: controls, run health and the log](run-tab.png)

| Part | What it does |
|---|---|
| Headlines from | **Live news** works on your data folder. **Demo headlines** switches the window to the demo folder (`./cassandra-demo-data`, or the folder given with `--gui --demo --data=DIR`) so demo runs can never mix with your real track record. Switching back returns to the live folder. |
| Run now (live) | Fetches today's headlines from the feeds on the Settings tab and runs exactly as a scheduled run would. The full report appears in the log. Running twice on one day replaces that day's history; predictions already made that day are kept. |
| Run next demo day (demo) | Runs the day after the demo folder's latest day, so the storyline moves on. |
| Simulate N days (demo) | Runs N demo days in a row, one summary line per day, with a progress bar. **Stop** finishes the current day and stops. The screenshot shows 35 days from an empty folder. |
| Run health | When the latest run was, any days missed in the last 30 (live mode), how many predictions were voided because no run graded them within the grace period, and how many days of headlines are archived for replay. Missed days usually mean the scheduled job isn't running. |
| Log | Everything a run prints. **Clear log** empties it. |

While a run is going, the run buttons, the mode switch, **Choose…** and **Save** are disabled. If a scheduled run happens to be saving to the same folder at that moment, the window's run stops without saving and says so in the log.

#### Settings tab

![The Settings tab: news feeds on the left, thresholds on the right](settings-tab.png)

Settings belong to a data folder and are saved in `cassandra.properties` there. **Every run in that folder reads them**: from the window, from the command line, and from cron.

| Part | What it does |
|---|---|
| News feeds | The RSS 2.0 feeds read on live runs. Paste an address and press **Add**; select feeds and press **Remove**; **Test selected** fetches them and reports how many headlines each returned (with nothing selected, it tests them all). **Restore default feeds** puts back the BBC and Guardian feeds. Feeds given on the command line take priority over this list. |
| Thresholds | Headlines needed for a run, stories that get predictions, rank penalty, trend look-back, weight of first guess, and the grace period. Each has its allowed range and a one-line explanation. |
| Save / Revert / Restore all defaults | **Save** writes the file; **Revert** goes back to what is saved; **Restore all defaults** fills in the defaults (save to keep them). "Unsaved changes" stays visible until you save or revert, even when the window reloads. |
| Test on archive… | Before saving, see whether your edits would have done better: replays the archived headlines with the saved settings and with your edits, and compares them. See [Replay](#replay). |

Changes apply from the next run. Past predictions are never re-scored, so changing thresholds partway through means the track record mixes two set-ups. If you want a clean comparison, use a fresh data folder, or test the change on the archive first.

The window uses your system's look, so on a Mac it will look slightly different from these screenshots.

## Replay

Every run that saves also keeps the exact headlines it analysed in the data folder's `archive/`. Replay runs Cassandra over those archived days again, in order, into a fresh folder: the same dates, the same run times, the same headlines. With the original settings it reproduces the original track record exactly; with different settings it shows what *would* have happened. Weeks of grading take seconds.

### In the window

On the **Settings** tab, change one or more thresholds, then press **Test on archive…** (before saving). Cassandra replays the archive twice in temporary folders, once with the saved settings and once with your edits, and shows the results side by side:

![Test on archive results: saved settings against the edits, with a verdict](test-on-archive.png)

The verdict compares the two skill scores against the base rate and says plainly when their intervals overlap, meaning the difference could be luck. Here, predicting for only the top story scored better, but not by enough to be sure on 35 days. Nothing in the data folder changes; **Save** your edits to use them, or **Revert** to discard them.

### On the command line

```bash
# baseline: replay with the folder's own settings into cassandra-data-replay
java Cassandra --replay=cassandra-data

# a variant: put the settings to try in a new folder, then replay into it
mkdir try-2-stories
echo "predicted.stories=2" > try-2-stories/cassandra.properties
java Cassandra --replay=cassandra-data --data=try-2-stories
```

Each replay prints one line per day, then the full accuracy report for the replayed folder and a comparison with the original runs. The replayed folder is an ordinary data folder, so you can also open it with `--report` or `--gui --data=…`. Replay refuses to write into a folder that already holds a track record.

What replay can and can't tell you:

- It tests **settings**: thresholds, and after code changes, priors and lexicons. It cannot test new feeds, since it only has the headlines that were archived.
- Changing the number of stories that get predictions changes **which** questions are asked, so the two sides are not graded on exactly the same predictions; the verdict says so when that happens.
- A setting tuned on the archive can fit its quirks. Treat a small improvement on a short archive as a hint, and check it again as the archive grows.

## Publishing on GitHub Pages

A forecaster can only be held to account, or be useful to anyone, if her predictions are public at the moment she makes them. `--publish` turns the data folder into a small static website:

```bash
java Cassandra --publish=site            # run as usual, then write the website into ./site
java Cassandra --publish-only=site       # write it from saved results, without running
```

![The published site: scorecard and today's forecasts](published-site.png)

| File | What it is |
|---|---|
| `index.html` | The scorecard (the same verdict as `--report`), the latest forecasts with probability bars and links to the stories, the reliability and weekly Brier charts, the 20 most recently graded predictions, and how to check it all. One self-contained page, no JavaScript, readable on a phone, light or dark to match the reader's system. |
| `feed.xml` | An RSS feed with one item per day of forecasts, so people can follow her in a feed reader. |
| `predictions.csv` | Every prediction she has ever made, with its probability, due date and outcome. |
| `predictions.sha256` | The CSV's SHA-256 fingerprint. Anyone can check a copy with `shasum -a 256 -c predictions.sha256`. |
| `since.txt` | The first day of publication. The scorecard reports accuracy since that day separately (see below). Leave it alone. |

Publishing writes files and nothing else: it does not post, upload or promote anything. Putting the folder online is your step.

**Demo data is labelled.** If the data comes from demo headlines, the page and the feed say so prominently, whether or not `--demo` was given.

### Putting it online

1. On GitHub, create an empty public repository, for example `cassandra-forecasts`.
2. Clone it as the site folder, next to your data: `cd ~/cassandra && git clone https://github.com/RDMachinery/cassandra-forecasts.git site`
3. In the repository's settings on GitHub, turn on GitHub Pages and serve it from the main branch's root folder. The site's address will be something like `https://rdmachinery.github.io/cassandra-forecasts/`.
4. Save this as `~/cassandra/publish.sh` and make it executable with `chmod +x publish.sh`:

   ```bash
   #!/bin/zsh
   # Run Cassandra, publish the website, and push it, recording the CSV's fingerprint in the commit.
   cd /Users/mario/cassandra || exit 1
   /usr/bin/java Cassandra --publish=site --site-url=https://rdmachinery.github.io/cassandra-forecasts/
   cd site || exit 1
   git add -A
   git commit -q -m "Forecasts for $(date -u +%F); predictions.csv sha256 $(cut -c1-64 predictions.sha256)" && git push -q
   ```

5. Point your scheduled job at the script instead of at `java`. With the hourly catch-up pattern from the cron tutorial:

   ```
   0 7-23 * * *  cd /Users/mario/cassandra && (grep -q "^$(date -u +\%F)" cassandra-data/history.tsv 2>/dev/null || ./publish.sh >> cassandra.log 2>&1)
   ```

`git push` from cron needs to work without you typing anything, so set up an SSH key or a credential helper for GitHub first, and test with `./publish.sh` by hand.

**Why git matters.** Each day's commit is a public, timestamped record of exactly what she predicted, and the commit message carries the fingerprint of the full prediction file. Predictions can't be quietly changed later without that showing in the history.

### The catch: forecasts that influence what they forecast

Cassandra predicts news *coverage*. If she ever became widely read, her forecasts could change the coverage they predict: a story called "likely to stay big" might be kept big partly because of the forecast, or dropped because "everyone's covering it". Her score would then measure some of her influence as well as her foresight. So the page reports **accuracy since the first day of publication separately**, and the README's accuracy figures should say which period they cover. For the same reason, Cassandra never acts on the news herself: no posting, sharing or promoting stories, which would turn forecasting into manipulation and make her track record meaningless.

## Known limitations

- **It cannot foresee events.** The predictions are about news coverage (what will be in the headlines, how big it will be, what tone it will have), not about the underlying events. The narrative is template-based and will sound plausible whatever the facts. Do not use any of it for financial, political or safety decisions.
- **No live track record yet.** The only accuracy figures so far come from synthetic demo data.
- **Early probabilities are guesses.** Until a prediction type has a few graded results her hand-set first guesses count for most, and with only a handful of results both her probabilities and the track record are statistically noisy.
- **The interval is approximate.** Resampling by day allows for predictions on the same day being linked, but not for links across days (the same story is predicted about on several days running), so the true uncertainty is somewhat wider.
- **Small groups learn slowly.** Rank and momentum are learned from the record, but a group with few results stays near the type's base rate, so real differences take a few weeks to show; and on short records the groups can disagree in odd ways (a 7-day forecast higher than the 3-day one).
- **A cold start takes time.** No trend appears until three different days have been recorded, the first grades arrive three days after the first run, and a verdict needs a few weeks.
- **Old tone predictions hid some misses.** A `TONE_PERSIST` claim (made before 2.3) is voided when its story has fallen below 3 headlines, which is exactly when it is most likely to have failed, so that type looks better than it is. Its replacement, `TONE_HOLDS`, counts a fading story as a miss.
- **The evidence is slow to earn a say.** It starts with none and its weight is held back by `n / (n + 20)`, so even evidence that genuinely helps takes weeks to count fully. In simulated news this cost about 0.003 on the Brier score of `TOP5@3`; on real headlines, where the evidence has so far pointed the wrong way, it is what kept her level with the base rate.
- **Records span several methods.** Predictions keep the method they were made with, so a record that started under 2.0 mixes hand-set guesses, the 2.0 base-rate method and the 2.1 blend. The report's "by how the probability was set" table separates them.
- **Story identity can drift.** A story is tracked by its top-ranked term; if the leading word changes from day to day, history for that story may be split, and it may be treated as new. Two stories that share their leading word ("midterm election" and "Israel election") are treated as one.
- **Word lists need upkeep.** `GENERIC` and `STOP2` were built from the first weeks of live headlines. New feeds bring new filler words and promotions; check the Story history tab now and then for "stories" that aren't. Plurals are stemmed crudely, so "James" is tracked as "jame".
- **Sentiment is crude.** A small word list cannot handle negation or sarcasm.
- **Coverage is not reality.** Feeds are editorial selections, and the result reflects what these outlets chose to publish.
- **Changing settings mid-record mixes set-ups.** Past predictions keep the settings they were made under, so the track record after a change blends two configurations. Test a change on the archive first.
- **Replay only knows archived headlines.** Archiving starts with version 2.0, it can't test feeds that weren't being read, and settings tuned on a short archive may not hold up on new days.
- **Published forecasts can influence what they forecast.** If the site became widely read, her score could partly reflect her influence on coverage rather than her foresight; accuracy since publication is reported separately for that reason.
- **Only RSS 2.0 `<item>` feeds are supported; English only.**

## Roadmap

Planned features, roughly in priority order. None of these are implemented yet.

### Better learning

- **Test the evidence on real headlines.** After a few weeks of live runs, replay the archive to see which evidence (rank, momentum, days in the top 5, trend, tone strength) actually carries information, and drop or add evidence accordingly.
- **Weigh each clue separately.** 2.3 gives each type's evidence one weight between them. Weighing each clue (rank, momentum, trend …) on its own would keep a useful clue from being silenced by a useless one.
- **More prediction types.** For example `LEAD` (the leading story will still lead in 3 days), `RISE` (a fast-growing story ranked 6–10 will break into the top 5), or `OUTLETS_UP` (more outlets covering a story in 3 days).
- **Per-type reliability correction.** If a type's high forecasts keep coming true less often than she says, stretch or squeeze its probabilities (a slope as well as a shift).
- **HTML accuracy report.** The window's charts as a self-contained HTML page, for sharing the track record.

### Smarter analysis

- **Stable story identity.** Match today's stories to yesterday's by overlap of their top terms, not by a single leading word, and give each story a persistent ID in `history.tsv`. (Simply keying stories by their two-word label was considered for 2.2 and rejected: labels change from day to day, "donald trump" one day and "trump administration" the next, so grading would have marked lasting stories as gone.)
- **Find generic words automatically,** for example words that appear in many unrelated stories over the history, instead of keeping `GENERIC` by hand.
- **Better `SHARE_UP` evidence.** On real headlines it is her weakest type: in the 2.2 replay its claims came true 46% of the time against an average forecast of 50%, yet it scored 0.380, worse than a coin flip, because her forecasts don't separate the stories that grow from the ones that don't.
- **Negation-aware sentiment.** Flip the polarity of a lexicon word preceded within two words by "not", "no", "never", "without" and similar.
- **Bigram story labels** used as the tracked key, so "port strike" and "general strike" are not the same story.

### Practicalities

- **Atom feed support** (`<entry>` elements) alongside RSS 2.0.
- **Polite fetching.** Send `If-Modified-Since` / `ETag` headers and skip unchanged feeds.
- **Machine-readable output (`--json`)** for dashboards or other tools.
- **LLM narrative (optional).** Replace the template narrative with a model call that receives the clustered headlines and Cassandra's track record, while keeping the templates as an offline fallback.

## Contributing

Issues and pull requests are welcome. If you change priors, lexicons or templates, include a short before/after example in the PR description, ideally with `--report` output from the same data before and after. Please check that `java Cassandra --selftest` passes, that a 35-day demo simulation (the window's **Run** tab, or `--demo` with `--asof`) still runs, and that its `--report` is no worse than before. After changing how probabilities are made, `--replay` over a real archive is the best test.

## Version history

### Version 2.3

Evidence that earns its say, and a tone claim that can't hide its misses. Written after replaying the first 12 days of real headlines, on which her evidence (rank, momentum, trend, tone strength, days in the top 5) was pointing the wrong way: the predictions she rated more likely came true *less* often. For example, 2.0 to 2.2 added 10 points to "still in the top 5" for an accelerating story, but only 2 of 6 accelerating stories stayed there.

#### Evidence that earns its say

- **Before:** the evidence always had its full say. Each piece moved the probability as far as the rate in its group suggested, shrunk only by the group's size.
- **Now:** each prediction records where she started (`q=`: the base rate blended with her first guess) and where the evidence would have taken her (`e=`). The probability issued is `q + v × (e − q)`, where `v` is the weight that would have scored best on the type's earlier graded predictions (least squares, kept between 0 and 1), shrunk by `n / (n + 20)`. The evidence starts with no say, and gets one only by showing on her own record that it helps.
- **The report says how it's going:** a new table, **HAS THE EVIDENCE EARNED ITS SAY?**, shows each type's Brier score without and with the evidence, and the weight earned. The output and the window's probability tooltips say what the evidence suggested and how much say it has.

#### `TONE_HOLDS` replaces `TONE_PERSIST`

- **Before:** "coverage will still be clearly negative". If the story had fallen below 3 headlines, its tone couldn't be measured and the claim was voided, so the stories most likely to have failed it were left out of every score.
- **Now:** "it will still be in the news, with coverage still clearly negative". A story below 3 headlines counts as a miss. The first guess is the old one times the chance the story is still in the news (92%, less 4 points per place below first), and rank is now part of the evidence.
- **Old tone predictions keep their rule.** `TONE_PERSIST` predictions still pending are graded as before, and stay in the record as their own type.

#### Results

- **Replay of 12 days of real headlines** (25 October to 5 November 2026):

  | | 2.1 | 2.2 | 2.3 |
  |---|---|---|---|
  | Brier, all graded | 0.288 | 0.267 | 0.256 |
  | Skill vs base rate | −0.07 (−0.14 to −0.02) | −0.05 (−0.16 to +0.05) | +0.02 (−0.03 to +0.08) |

  Type by type, 2.3 against 2.2: `TOP5@3` 0.196 against 0.209 (skill +0.02 against −0.08), `SHARE_UP@3` 0.345 against 0.364 (+0.03 against −0.05), `TOP5@7` unchanged at 0.204. On these days every story given a tone prediction stayed in the news, so `TONE_HOLDS` and `TONE_PERSIST` graded the same. The evidence earned no say on any type: without it, Brier 0.188, 0.344 and 0.251 for `TOP5@3`, `SHARE_UP@3` and `TONE_HOLDS@3`; with it, 0.215, 0.373 and 0.260.
- **Three simulated 90-day worlds:** still ahead of the base rate in all three (skill +0.11, +0.06 and +0.10, against 2.2's +0.12, +0.09 and +0.13). The evidence helps in these worlds and earned 20–91% of a say depending on the type, but starting from none costs a little (see [On a simulated news world](#on-a-simulated-news-world)). Between a quarter and a third of tone claims in these worlds concern stories that fade, which `TONE_PERSIST` voided.
- **35 demo days:** skill −0.01 (−0.03 to −0.00), Brier 0.184 against the base rate's 0.181 on comparable predictions: level in practice, though the interval just excludes zero. A lighter first-guess weight (`calibration.min` 2) made no difference on the demo and was worse on real headlines (+0.02 fell to −0.01), so the default stays at 5.
- 22 self-tests pass (two new: evidence earning its say, and a fading story counting as a miss for `TONE_HOLDS` but a void for `TONE_PERSIST`).

### Version 2.2

Stories that are stories. Her first two weeks on real headlines showed that a third of her graded predictions (37 of 106) were about things that weren't stories: newsletter promotions, amounts and everyday words.

#### What was wrong

- **"Sign up" was her most frequent story.** Guardian summaries carry promotions ("Don't get Pushing Buttons delivered to your inbox? Sign up here", "Get our breaking news email, free app or daily news podcast"), dozens a day. "up" was in her top 10 on 13 of the 16 days she had run.
- **Amounts became words.** "$6.3bn" was split into "bn", which became a story.
- **Everyday words led stories.** "government" was in the top 10 on 10 days and stood for six unrelated stories ("form government", "government spokesperson", "federal government" …). "Will it still be in the top 5?" was really asking whether a word would keep turning up. "state", "time", "help", "leader", "world" and "street" did the same.
- **"won't" counted as "won"**, a positive word, in tone.

#### What changed

- **Promotions are stripped** (`PROMOS`) when feeds are read *and* when headlines are analysed, so archives saved by older versions replay cleanly.
- **Words containing digits are dropped**, and "n't" is read as "not".
- **A second stopword list** (`STOP2`) for story detection only: "up", "them", contraction stubs, days of the week, months, and words such as "since", "ago" and "including".
- **Generic words and tone words can't lead a story** (`GENERIC`, `NEG`, `POS`). They still count in labels, tone and related terms, so "port strike" is still labelled "port strike", though it is tracked by "port".
- **Old predictions keep their rules.** Each prediction records the edition of the story-detection rules (`d=2`). A prediction without it is graded against a snapshot built with the 2.1 rules, so upgrading never rescores claims already made. This was checked by replaying 5 archived days with 2.1 and the next 5 with 2.2: all 47 predictions made under 2.1 were graded exactly as a pure 2.1 replay grades them.

#### Results

- **Replay of 10 days of real headlines** (25 October to 3 November 2026, 10 feeds, 5 stories a day). Every story given predictions is now a real story ("donald trump", "midterm election", "budget chancellor", "binance hack", "iran qeshm", "cyber attack" …), against 21 predictions about "up", 21 about "government" and 4 about "bn" before.

  | | 2.1 | 2.2 |
  |---|---|---|
  | Brier, all graded | 0.301 | 0.275 |
  | `TOP5@3` Brier | 0.284 | 0.203 |
  | `TOP5@7` Brier | 0.294 | 0.198 |
  | `SHARE_UP@3` Brier | 0.335 | 0.380 |
  | Skill vs base rate | −0.07 (−0.16 to +0.00) | +0.00 (−0.17 to +0.11) |

  "Still in the top 5" is now a question about stories, and she does much better on it. `SHARE_UP` got worse and remains her weak point. Ten days is too short for the skill intervals to separate.
- **Three simulated 90-day worlds:** Brier 0.170, 0.179 and 0.177, from 0.178, 0.182 and 0.194, and she now beats the base rate in all three (see [What the report looks like](#what-the-report-looks-like)).
- **35 demo days:** unchanged within noise (skill −0.02, Brier 0.158); the demo's headlines have no promotions or filler stories.
- 20 self-tests pass (two new: promotions, amounts and contractions; and generic words with old-rules grading).

#### Why not track stories by their two-word label?

It was considered and rejected. Labels shift from day to day ("donald trump", then "trump administration"), so a lasting story would have been graded as having left the top 5. Single leading words are steadier once generic words are excluded. Proper story identity is on the [roadmap](#roadmap).

### Version 2.1

Fewer bets on change, and a smoother hand-over from her guesses to her record. Written after her first two weeks on real news, when she was measurably worse than the base rate (skill −0.22, interval −0.34 to −0.13, on 41 comparable predictions). The clearest fault: she gave "this story will take a bigger share of the headlines" about a 50% chance, and it happened 25% of the time, while she under-rated how often stories and their tone simply stay put.

#### First guesses that expect things to stay put

A story is predicted about *because* it is one of today's biggest, so its share today is usually near its peak and more often falls than rises (regression to the mean). Leading stories and their tone also tend to last.

- **`SHARE_UP`:** with a trend, 12% plus half the probability the trend is rising (was the probability itself, 0–100%); without one, 40% accelerating, 30% new, 25% steady or unclear, 15% fading (was 55%, 45%, 42%, 30%).
- **`TOP5@7`:** 5 points below `TOP5@3` (was 15). Her live `TOP5@7` claims came true 80% of the time against an average forecast of 53%.
- **`TONE_PERSIST`:** 68%, 75% or 82% by tone strength (was 58%, 66%, 72%). Live, these came true 83% of the time against 64% forecast.

#### A smooth hand-over to the record

- **Before:** her first guess was used until a type had 5 graded results; from the 5th it was dropped entirely and she started from the base rate, trusting a rate measured on just 5 results.
- **Now:** from the first graded result she starts from `(hits + 5 × guess) / (results + 5)`: the guess counts as 5 results, so it fades as the record grows instead of vanishing at once. The weight is the existing `calibration.min` setting, relabelled **Weight of first guess** in the window; its old meaning (results needed before recalibrating) no longer applied, because from the 5th result the base rate had always taken over.
- **Her guesses are still corrected for bias,** but only by results from guesses made under the current rules. Each prediction records the edition of the rules (`g=2`); without this, her old over-eager `SHARE_UP` guesses would drag the new, more cautious ones further down.

#### More evidence

- **`TOP5`** now also uses how many days in a row the story was already in the top 5 (`k=0`, `1-2` or `3+`).
- **`SHARE_UP`** now also uses the story's rank.
- Each prediction also records how many results its base rate rested on (`n=`), and the output says so: `[base rate 85% over 83 results and first guess 82%, adjusted for the evidence]`.

#### Seeing which method the score reflects

- `--report` has a new table, **BY HOW THE PROBABILITY WAS SET**, splitting the comparable predictions into hand-set first guesses, the 2.0 base-rate method and the 2.1 blend, each with its own Brier score and skill. A record started before 26 October 2026 holds all three, and the old predictions stay in it for good.

#### Results

- **35 demo days:** skill −0.02 (interval −0.08 to +0.03), up from −0.04 (−0.08 to −0.01), which was measurably worse than the base rate; Brier 0.159, from 0.164; first guesses alone 0.175, from 0.207. See [What the report looks like](#what-the-report-looks-like).
- **Three simulated 90-day news worlds:** better or level in the first 20 days (Brier 0.203, 0.192, 0.225 against 0.201, 0.206, 0.234) and level after 90.
- 18 self-tests pass (two new: the blend with the record, and learning only from current-rules guesses). Old `predictions.tsv` files load unchanged, and replay still reproduces runs exactly.

#### Documentation

- Earlier copies of this guide and the README described features that are not in the program: `TONE_HOLDS`, `LEAD` and `RISE` predictions, challengers, evidence weights (`e=`, `w=`) and a richer demo. They have been moved to the [roadmap](#roadmap). They also said `--selftest` was never part of the program; it is, and it is now documented.

### Version 2.0

#### Momentum fixed, and probabilities start from the base rate

Two changes to how she forecasts, and a courtesy to the sites she reads.

- **Momentum now measures what it says.** It used to compare articles from the last 8 hours of one fetch with the 24 hours before. Feeds mostly carry recent items, so the earlier window was nearly always thin and momentum read "accelerating" on every one of 35 demo runs. That quietly added 10 points to every "still in the top 5" forecast. Momentum now compares a story's share of headlines today with its average over the previous 3 days of runs, and must change by at least 3 points and 30% to count as accelerating or fading. A story missing from those runs is **new**; with no runs to compare against, momentum is **unclear**. On the same 35 demo days the dominant story was now steady 22 times, accelerating 11, fading once and unclear once. The report shows the numbers behind it, e.g. `Momentum: steady (33% of headlines today against 34% on average over the previous 3 days)`.
- **Every probability starts from the base rate.** Once a prediction type has 5 graded results, a new prediction begins at how often that type has come true so far, then moves only as far as the record shows its evidence matters: rank and momentum for "still in the top 5", the trend's direction and momentum for "bigger share", and the tone's strength for "tone persists". Each step blends a group's own rate with the step before it, `(hits + 10 × parent) / (results + 10)`, so a group with few results stays close to the base rate. Her old hand-set probabilities are now only her first guesses, used while a type has no record.
- **Each prediction records its evidence** in a new last column of `predictions.tsv` (for example `r=2;m=steady;b=0.8500`), which is what lets later predictions learn from it. Older files load unchanged.
- **The report shows where each probability came from**, e.g. `-> 86% [from the base rate of 88%; first guess 87%]`.
- **Results on the same 35 demo days:** skill against the base rate improved from −0.17 to −0.04, the Brier score from 0.178 to 0.164, and the weakest type (`SHARE_UP`) from −0.28 to −0.10. She is still slightly short of the base rate, which is expected on demo data, where there is no real signal for the evidence to find. Real headlines and the replay archive will show whether it helps.
- **User-Agent** is now `Cassandra News Forecaster - https://github.com/RDMachinery/Cassandra`, so the sites she reads can see what she is and where to find out more.
- Replay still reproduces runs exactly with the new code.

#### Publishing (`--publish`)

Her forecasts can now be made public and verifiable from the moment they're made. See [Publishing her forecasts](#publishing-on-github-pages).

- **`--publish=SITE`** runs as usual, then writes a static website into SITE; **`--publish-only=SITE`** writes it from saved results without running
- **`index.html`:** the scorecard, the latest forecasts with links to the stories, the reliability and weekly Brier charts, recently graded predictions, and how to check it all. One self-contained page with no JavaScript, readable on a phone and in dark mode.
- **`feed.xml`:** an RSS feed, one item per day of forecasts
- **`predictions.csv` and `predictions.sha256`:** every prediction ever made, and its fingerprint for anyone to check
- **Accuracy since publication is shown separately,** because public forecasts can influence the coverage they predict
- **Demo data is labelled** on the page and in the feed, even if `--demo` wasn't given
- **Nothing is posted anywhere.** Publishing writes files; the [publishing section](#putting-it-online) shows how to put them on GitHub Pages and push them daily from cron, with the fingerprint in each commit message

#### Headline archive and replay

Settings changes can now be tested against real past headlines in seconds, instead of waiting weeks for new grades. See [Testing changes with replay](#replay).

- **Every saving run archives the headlines it analysed**, together with the moment it ran, in `archive/YYYY-MM-DD.tsv` inside the data folder. Re-running a day replaces that day's file.
- **`--replay=DIR`** re-runs Cassandra over DIR's archive, day by day, into a fresh folder (`--data=OUT`, default `DIR-replay`), using the settings in that folder's `cassandra.properties` if it has one. It then prints the accuracy report and a comparison with the original runs.
- **Exact:** each day replays with the same date, run time and headlines, so with the original settings a replay reproduces the original track record byte for byte. This was checked on 35 days of demo runs and on a live run with real article links.
- **Safe:** replay refuses to write into a folder that already holds a track record, so two records can never be mixed.
- **Test on archive…** on the Settings tab replays the archive twice, once with the saved settings and once with your unsaved edits, and shows the two side by side with an honest verdict: if the skill intervals overlap, it says the difference could be luck. Nothing in the data folder changes.
- **Run health** on the Run tab now also says how many days of headlines are archived.

The archive starts with this version: folders from earlier versions have nothing to replay until they have run a few more times.

#### Run and Settings tabs

The last two desktop-window items from the roadmap. The window can now run Cassandra and change her settings, not just show results. See [Run tab](#run-tab) and [Settings tab](#settings-tab).

**Run tab**

- **Run now** fetches today's headlines and runs exactly as a scheduled run would, with the full report in a live log
- **Demo mode** switches the window to the separate demo folder (`./cassandra-demo-data`, as with `--demo`), so demo runs never touch your real track record. There, **Run next demo day** advances the demo storyline by a day, and **Simulate N days** runs many days in one go, with a progress bar and a Stop button.
- **Run health:** the date of the latest run, any **days missed** in the last 30 (so you can spot a broken scheduled job), and how many predictions were voided because no run graded them in time

**Settings tab**

- **News feeds:** add, remove and test feeds (testing shows how many headlines each one returns), or restore the defaults
- **Thresholds:** headlines needed for a run, stories that get predictions, rank penalty, trend look-back, results before recalibrating, and the grace period, each with a one-line explanation
- **Save** writes them to `cassandra.properties` in the data folder. **Every run in that folder uses them, scheduled ones included**, so nothing needs recompiling. Unsaved edits are kept through the automatic reload.

**Behind the scenes**

- **Settings file.** `cassandra.properties` holds the feeds and thresholds for its folder, and is plain text you can also edit by hand. Values out of range are reported and ignored. With no file, everything works exactly as before. Feeds given on the command line still take priority.
- **Run lock.** A run now holds a lock on its data folder (`cassandra.lock`) while it reads and saves. If a scheduled run and a run from the window ever overlap, the second stops without saving and says so, instead of the two overwriting each other.
- Checked: with no settings file, terminal output and saved data are unchanged over 35 days of demo runs; and 35 days simulated in the window produce data identical to 35 separate command-line runs.

#### Story history tab

A fourth tab in the desktop window: pick any story Cassandra has tracked and see its whole life on one timeline. See [Desktop window](#story-history-tab) for a tour.

- **A searchable list of every story** that has been in the top 10, with how many runs it spent there, its best rank, when it was last seen and how many predictions it drew
- **A summary for the chosen story:** its spell in the top 10, its best rank, and how her predictions about it fared (hit rate against average forecast, and Brier score)
- **One timeline in three bands sharing a date axis:**
  - its **share of headlines**, run by run, with gaps where it dropped out of the top 10
  - its **rank**, with the top 5 shaded, since that is what the `TOP5` predictions are about
  - **every prediction made about it**, one lane per type, marked at its due date: came true, didn't happen, pending or void
- **Hover** any dot or marker for its figures, or a prediction for its full wording, forecast and outcome
- **Show its predictions** opens the Predictions tab filtered to that story
- **Double-click a story on the Today tab** to jump straight to its history
- The chosen story and any search **survive the automatic reload** after a scheduled run

#### Predictions tab

A third tab in the desktop window: the complete ledger of every prediction Cassandra has made. See [Desktop window](#predictions-tab) for a tour.

- **One row per prediction:** when it was made and due, the story, what it claims, its type, the probability she gave (and before recalibration), the outcome and when it was graded
- **Sort by any column**; shift-click a second heading to sort by two
- **Filter** by outcome (pending, came true, didn't happen, void), by prediction type, or by searching for a story or word
- **A live summary of what's shown:** how many came true against her average forecast, and the Brier score. Filter to one type or one story to see how she does on just that.
- **Export CSV** saves the rows currently shown, in the order shown, with the full wording of each prediction
- **Filters and sort order survive** the automatic reload after a scheduled run
- **Read-only:** outcomes can be inspected and exported, never edited
- **About box:** Help → About Cassandra (on a Mac, also in the Cassandra menu) shows the version, licence and GitHub page

#### Today tab

The desktop window now opens on a **Today** tab showing what the latest run found. See [The desktop window in detail](#the-desktop-window-in-detail) for a tour.

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

#### Desktop window (`--gui`)

A Swing window that shows Cassandra's accuracy as charts instead of text, on its **Accuracy** tab:

- **Verdict banner** in green, red, amber or grey: beating the base rate, worse than it, no clear edge, or too early to judge
- **Headline figures**: skill against the base rate with its interval, Brier score, what a coin flip would score, and what recalibration has gained
- **Reliability diagram**: forecast probability against how often it came true, with a 90% range for each band
- **Brier score by week** for Cassandra, Cassandra before recalibration and the base-rate forecaster, with the coin-flip line for reference
- **Per-type table**, with skill shown in colour once a type has enough comparable predictions
- **Hover** over a dot or a week for its exact figures
- **Reloads by itself** when a scheduled run saves new results while the window is open
- **No way to change past outcomes**: the window can now run Cassandra and save settings (see the Run and Settings tabs), but grading is always automatic

#### Under the hood

- **The engine is separate from the printing.** A run now produces a result object (`forecastRun` → `RunResult`), and so does the accuracy report (`computeAccuracy` → `Accuracy`). The terminal printers and the GUI only read these. Terminal output and saved files are byte-for-byte unchanged; this was checked against 35 days of demo runs.
- **Safe saves.** `history.tsv` and `predictions.tsv` are written to a temporary file and then renamed into place, so anything reading them, such as the window, never sees a half-written file.

#### Earlier in this release

- **Accuracy report (`--report`).** Skill score against a base-rate forecaster with a 90% interval and a plain-English verdict, Brier scores against three benchmarks, a per-type breakdown, a reliability table and weekly Brier scores. The end-of-run track record also gives the skill score. See [How accurate is she?](#how-she-is-scored).
- **Predictions for the top 3 stories.** Each of the top 3 stories gets its own predictions, so about three times as many are graded each day and recalibration starts within the first week. Lower-ranked stories start with a lower `TOP5` probability (the `rank.penalty` setting).
- **Fair trends for new stories.** The trend is fitted only from the day a story first appeared in the top 10, instead of counting the days before as 0% coverage. New stories are labelled "new today", and the forecast explains when there is not yet enough data for a trend.
- **Livelier demo.** `--demo` headlines change from day to day: a fading port strike, a steady AI chip story, and a storm that breaks on 3 October and grows.
- **Tidier output.** Wrapped prediction lines have a hanging indent.

Existing `history.tsv` and `predictions.tsv` files work unchanged.
