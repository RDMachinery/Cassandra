# Miranda

> Reads the news, finds the dominant story, and writes a plain-English scenario of what might happen next.

NewsOracle is a small, dependency-free Java program that pulls headlines from RSS feeds, works out which story is dominating the news, measures its tone and momentum, and then describes a plausible chain of consequences over the coming days, weeks and months.

It is a **transparent, rule-based heuristic**, not a genuine forecasting engine. See [Limitations](#limitations) before reading anything into its output.

## Features

- Fetches and parses multiple RSS 2.0 feeds (BBC and Guardian by default, or bring your own)
- Identifies the dominant story by breadth of coverage, number of distinct outlets and headline prominence
- Labels the story with a recurring two-word phrase where possible (e.g. "port strike")
- Estimates **tone** (negative / mixed / positive) using a sentiment lexicon
- Estimates **momentum** (accelerating / steady / fading) from publication times
- Classifies the story into a **domain**: conflict, economy, tech, politics, climate, health or general
- Generates a three-stage narrative plus an **alternative branch** and a confidence rating
- Lists other storylines worth watching
- Offline `--demo` mode for testing without a network connection
- Pure JDK: no Maven, no Gradle, no third-party libraries

## Requirements

- Java 16 or later (the code uses records and switch expressions)
- An internet connection for live feeds (not needed for `--demo`)

## Quick start

```bash
git clone https://github.com/<your-username>/newsoracle.git
cd newsoracle

# Compile and run
javac NewsOracle.java
java NewsOracle
```

If you only have a JRE, or want to skip the compile step, Java's single-file launcher works too:

```bash
java NewsOracle.java
```

## Usage

```bash
java NewsOracle                    # use the default feeds
java NewsOracle URL1 URL2 ...      # use your own RSS feeds
java NewsOracle --demo             # run offline on built-in synthetic headlines
```

Example with custom feeds:

```bash
java NewsOracle https://feeds.bbci.co.uk/news/science_and_environment/rss.xml \
                https://www.theguardian.com/science/rss
```

## Example output

The following was produced by `--demo` mode, which uses synthetic headlines, not real news:

```
==========================================================================================
 NEWS ORACLE - scenario generated from 10 headlines
==========================================================================================

DOMINANT STORY: "port strike"
Mentioned in 7 of 10 articles across 3 outlet(s). Domain: economy. Overall tone: negative
(net sentiment -2.14 per article). Coverage momentum: accelerating. Frequently linked
with: shipping, delays, prices.

PROJECTED SEQUENCE OF EVENTS
  Next few days: Markets react nervously to "port strike" (touching shipping, delays and
  prices): volatility rises and businesses delay spending and hiring decisions. Because
  coverage is accelerating, developments are likely to arrive faster than usual.

  Coming weeks: Higher costs feed through to consumer prices and borrowing; policymakers
  face pressure to respond, and weaker sectors begin to report losses.

  Coming months: If the weakness persists, slower growth and job losses invite fresh
  policy intervention and political blame.

ALTERNATIVE BRANCH
  If the early signs above do not hold: Easing costs and stronger demand feed through to
  consumers; policymakers gain room to ease conditions and healthy sectors report gains.
  If the momentum persists, growth firms up, though it may bring new worries about
  overheating and uneven gains.

CONFIDENCE: MODERATE
```

*(Output abridged; the full run also lists sample headlines and other storylines.)*

## How it works

1. **Gather.** Each feed is downloaded and parsed with the JDK's XML parser (DOCTYPE declarations are disabled to block XXE attacks). HTML is stripped and duplicate headlines are removed.
2. **Tokenise.** Titles and descriptions are lower-cased, split into words, stripped of stopwords and lightly stemmed (trailing plural "s").
3. **Rank topics.** Every term appearing in at least three articles gets a score:
   `articles + 1.5 × (distinct outlets − 1) + headline hits`. The highest-scoring term becomes the dominant story.
4. **Measure the story.**
   - *Tone:* average of (positive words − negative words) per article. At or below -0.5 is negative, at or above +0.5 is positive, otherwise mixed.
   - *Momentum:* article rate in the last 8 hours compared with the preceding 24. Fewer than four dated articles gives "unclear".
   - *Domain:* the keyword lexicon with the most hits across the story's articles (at least two hits required, otherwise "general").
5. **Narrate.** Hand-written causal templates for the chosen domain and tone are filled in for three time horizons. The opposite tone's later stages are shown as an alternative branch.
6. **Rate confidence.** Based on the number of articles, the number of outlets, and how one-sided the tone is.

## Customising

Everything lives in `NewsOracle.java`, and most tuning is a matter of editing constants:

| What to change | Where |
|---|---|
| Default feeds | `DEFAULT_FEEDS` |
| Minimum articles needed to produce a report | `MIN_ARTICLES` |
| Output line width | `WRAP` |
| Stopwords | `STOP` |
| Sentiment vocabulary | `NEG`, `POS` |
| Domain keywords | the `LEX` static block |
| Narrative templates | `chain(Domain)` (each row is one time horizon: negative variant, then positive variant) |
| Momentum windows | `momentum(List<Doc>)` |

To add a new domain, add a value to the `Domain` enum, a keyword set in `LEX`, and a case in `chain()`.

## Limitations

- **It does not predict the future.** It matches headlines to templates I wrote in advance. It will produce a confident-sounding narrative whether or not that narrative is likely. Treat it as a thought experiment, and do not use it for financial, political or safety decisions.
- **Sentiment is crude.** A small word list cannot handle negation, sarcasm or context ("no attack" scores as negative).
- **Frequency is not importance.** The most-mentioned term is not always the most significant story, and a single story spread across many near-synonyms can be under-counted.
- **Only RSS 2.0 `<item>` feeds are supported.** Atom feeds are ignored.
- **English only.**
- **Momentum is short-term.** It is based on a roughly 32-hour window and publication timestamps, which feeds do not always report accurately.

## Roadmap ideas

- Replace the template step with an LLM call that takes the clustered headlines as input
- Proper named-entity extraction (e.g. Stanford CoreNLP or OpenNLP) instead of word frequency
- Persist results daily so momentum can be measured over weeks
- Atom feed support
- Unit tests for tokenising, scoring and momentum
- Multi-language support

## A note on feeds

Check the terms of use of any feed you point this at. The defaults are public RSS feeds intended for headline syndication; the program sends a descriptive `User-Agent` and makes one request per feed per run. Please don't run it in a tight loop.

## Contributing

Issues and pull requests are welcome. If you add or change templates or lexicons, please include a short before/after example in the PR description.

## License

Released under the MIT License. See `LICENSE` for details.
