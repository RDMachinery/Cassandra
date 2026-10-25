import javax.swing.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Cassandra - a news-driven forecaster.
 *
 * Each run, Cassandra reads headlines from RSS feeds, finds the leading stories, and then:
 *   1. fits a trend to each story's share of coverage since it first appeared and projects it forward;
 *   2. issues explicit, dated, falsifiable predictions with probabilities for the top 3 stories;
 *   3. writes a plain-English scenario of the likely consequences;
 *   4. grades every earlier prediction that has now fallen due, reports her track record
 *      (hit rate and Brier score), and uses that record to recalibrate future probabilities.
 *
 * Nobody can see the future. What Cassandra can do is commit to testable claims, keep score,
 * and correct her own over- or under-confidence over time. Run her daily (e.g. via cron) so
 * her history and track record accumulate.
 *
 * Requires Java 16+. No external libraries.
 *
 *
 * Options:  --demo          offline synthetic headlines (data in ./cassandra-demo-data)
 *           --data=DIR      where history/predictions are kept (default ./cassandra-data)
 *           --no-log        analyse only; do not read or write any saved state
 *           --asof=DATE     pretend today is DATE (yyyy-mm-dd); for demos and backtests only
 *           --report        accuracy report from saved predictions: skill against a base-rate forecaster,
 *                           reliability table and weekly Brier scores. Fetches nothing.
 *           --gui           open the desktop window (Accuracy tab). Reads saved state only; fetches nothing.
 *           --help
 */
public class Cassandra {

    // ------------------------------------------------------------------ config

    static final List<String> DEFAULT_FEEDS = List.of(
            "https://feeds.bbci.co.uk/news/world/rss.xml",
            "https://feeds.bbci.co.uk/news/business/rss.xml",
            "https://feeds.bbci.co.uk/news/technology/rss.xml",
            "https://www.theguardian.com/world/rss",
            "https://www.theguardian.com/business/rss",
            "https://www.theguardian.com/technology/rss");

    static final int MIN_ARTICLES = 8;
    static final int WRAP = 90;
    static final int HISTORY_DAYS = 14;        // how far back the trend fit looks
    static final int MIN_FOR_CALIBRATION = 5;  // resolved predictions needed before recalibrating
    static final int GRACE_DAYS = 2;           // a prediction not graded within this window is void
    static final int PREDICTED_STORIES = 3;    // how many of the leading stories get predictions
    static final double RANK_PENALTY = 0.07;   // TOP5 prior is lowered by this much per rank below #1
    static final LocalDate DEMO_START = LocalDate.of(2026, 10, 1); // day 0 of the demo storyline

    static Instant NOW = Instant.now();
    static LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    enum Domain { CONFLICT, ECONOMY, TECH, POLITICS, CLIMATE, HEALTH, GENERAL }

    enum Tone { NEGATIVE, MIXED, POSITIVE }

    // ------------------------------------------------------------------ data

    /** One headline. link is the article's web address from the feed, or "" when there is none (e.g. demo data). */
    record Article(String source, String title, String body, Instant published, String link) {}

    record Doc(Article article, List<String> tokens, Set<String> terms, Set<String> titleTerms) {}

    /** One line of saved history: how a story stood on a given date. */
    record HistRow(LocalDate date, String term, int rank, int count, int total, double net) {
        double share() { return total == 0 ? 0 : (double) count / total; }
    }

    /** One prediction. outcome: PENDING, TRUE, FALSE, VOID (could not be graded). */
    record Pred(String id, LocalDate made, LocalDate due, String term, String label, String kind,
                String param, double pRaw, double p, String outcome, String resolved) {
        Pred with(String newOutcome, String when) {
            return new Pred(id, made, due, term, label, kind, param, pRaw, p, newOutcome, when);
        }
        boolean graded() { return outcome.equals("TRUE") || outcome.equals("FALSE"); }
        int days() { return (int) ChronoUnit.DAYS.between(made, due); }
        String key() { return kind + "@" + days(); }
    }

    record Stat(int n, int hits, double meanRaw, double brier) {}

    record Forecast(int n, double slope, double fittedToday, double sd, double xbar, double sxx) {
        double at(double h) { return fittedToday + slope * h; }
        double predSd(double h) {
            double d = h - xbar;
            return sd * Math.sqrt(1 + 1.0 / n + d * d / sxx);
        }
    }

    /**
     * One of today's leading stories, fully measured.
     * firstSeen: earliest earlier run (within the look-back window) on which the story was in the top 10,
     * or null if it is new today. points: number of daily observations the trend fit can use.
     */
    record Story(int rank, String term, List<Doc> docs, String label, double net, Tone tone,
                 String momentum, double share, LocalDate firstSeen, int points, Forecast fc) {}

    /** Today's picture of the news, used both to make and to grade predictions. */
    static class Snapshot {
        final Map<String, List<Doc>> index;
        final List<String> reps;
        final int total;

        Snapshot(Map<String, List<Doc>> index, List<String> reps, int total) {
            this.index = index; this.reps = reps; this.total = total;
        }

        double share(String term) {
            return total == 0 ? 0 : index.getOrDefault(term, List.of()).size() / (double) total;
        }

        boolean inTop5(String term) {
            List<Doc> mine = index.getOrDefault(term, List.of());
            if (mine.isEmpty()) return false;
            for (int i = 0; i < Math.min(5, reps.size()); i++) {
                String r = reps.get(i);
                if (r.equals(term) || overlap(mine, index.get(r)) > 0.6) return true;
            }
            return false;
        }

        OptionalDouble net(String term) {
            List<Doc> ds = index.getOrDefault(term, List.of());
            if (ds.size() < 3) return OptionalDouble.empty();
            return OptionalDouble.of(ds.stream().mapToInt(Cassandra::netSentiment).average().orElse(0));
        }
    }

    /** stem -> (surface form -> count), so we can print readable words. */
    static final Map<String, Map<String, Integer>> FORMS = new HashMap<>();

    // ------------------------------------------------------------------ vocab

    static final Set<String> STOP = setOf("""
            the and for are but not you all any can had her was one our out has have been from this that with they
            will would could should their there what when where which while who whom whose why how about after again
            against also because before being between both did does doing down during each few further here into
            just more most other over own same some such than then these those through under until very were your
            says said say continue new news year years day days week weeks today latest live first last may might must now
            people one two three still back off get gets got take takes make makes made see seen like told way
            report reports reported video watch analysis opinion editorial per cent amid among set uk us
            bbc guardian its his him she he we me my is it in on at to of a an as be by do if or so no go am""");

    static final Set<String> NEG = stemSet("""
            attack kill killed death dead crisis war fear threat warn warning collapse crash fall fell drop loss
            ban fail failure accuse accused charge protest violence strike damage risk worst concern hit decline
            slump cut cuts fire shortage conflict unrest hack breach scandal fine lawsuit sue sanction tension
            escalate""");

    static final Set<String> POS = stemSet("""
            win won deal agree agreement growth rise boost recover recovery record success hope peace ceasefire
            breakthrough support launch improve gain approve relief praise celebrate save invest investment
            surge rally cooperation""");

    static final Map<Domain, Set<String>> LEX = new EnumMap<>(Domain.class);
    static {
        LEX.put(Domain.CONFLICT, stemSet("""
                war attack military troop missile ceasefire strike army killed bomb hostage defence defense drone
                invasion soldier rebel militia gaza israel ukraine russia border security weapons"""));
        LEX.put(Domain.ECONOMY, stemSet("""
                economy economic inflation rates bank market stocks price trade tariff jobs growth recession debt
                budget tax shares oil energy business cost pound dollar sales firm profit supply wages"""));
        LEX.put(Domain.TECH, stemSet("""
                ai tech technology google apple microsoft meta software data cyber hack chip openai robot app
                online digital algorithm social internet startup model cloud"""));
        LEX.put(Domain.POLITICS, stemSet("""
                election government minister prime president vote party parliament labour conservative trump
                policy law court mps senate congress campaign leader coalition referendum"""));
        LEX.put(Domain.CLIMATE, stemSet("""
                climate weather flood storm heat wildfire emissions carbon drought hurricane temperature
                environment renewable green warming"""));
        LEX.put(Domain.HEALTH, stemSet("""
                health nhs hospital virus vaccine disease cancer doctors patients drug outbreak medical covid
                care mental clinic"""));
    }

    static Set<String> setOf(String words) {
        return Arrays.stream(words.replace("\"", " ").split("\\s+"))
                .filter(s -> !s.isBlank()).collect(Collectors.toSet());
    }

    static Set<String> stemSet(String words) {
        return setOf(words).stream().map(Cassandra::stem).collect(Collectors.toSet());
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) throws IOException {
        boolean demo = false, log = true;
        String dataArg = null;
        boolean asof = false, accuracy = false, gui = false;
        List<String> feeds = new ArrayList<>();

        for (String a : args) {
            if (a.equals("--demo")) demo = true;
            else if (a.equals("--report")) accuracy = true;
            else if (a.equals("--gui")) gui = true;
            else if (a.equals("--no-log")) log = false;
            else if (a.startsWith("--data=")) dataArg = a.substring(7);
            else if (a.startsWith("--asof=")) {
                TODAY = LocalDate.parse(a.substring(7));
                NOW = TODAY.atTime(12, 0).toInstant(ZoneOffset.UTC);
                asof = true;
            } else if (a.equals("--help") || a.equals("-h")) { usage(); return; }
            else feeds.add(a);
        }
        if (asof && !demo)
            System.out.println("Warning: --asof is meant for --demo/backtests; live headlines are dated in real time.\n");

        Path dir = Paths.get(dataArg != null ? dataArg : demo ? "cassandra-demo-data" : "cassandra-data");
        if (gui) { Gui.open(dir); return; }
        if (accuracy) { accuracyReport(dir); return; }
        List<Article> articles = new ArrayList<>();

        if (demo) {
            articles = demoArticles();
            System.out.println("[demo mode: synthetic headlines, not real news]\n");
        } else {
            for (String url : feeds.isEmpty() ? DEFAULT_FEEDS : feeds) {
                try {
                    List<Article> got = fetchFeed(url);
                    System.out.printf("Fetched %2d items from %s%n", got.size(), url);
                    articles.addAll(got);
                } catch (Exception e) {
                    System.out.printf("Could not read %s (%s)%n", url, e.getMessage());
                }
            }
            System.out.println();
        }

        articles = dedupe(articles);
        if (articles.size() < MIN_ARTICLES) {
            System.out.println("Only " + articles.size() + " articles available - not enough signal to say "
                    + "anything meaningful. Check your network connection or feed URLs.");
            return;
        }
        report(articles, dir, log);
    }

    static void usage() {
        System.out.println("""
                Cassandra - news-driven forecaster
                  java Cassandra [options] [feed-url ...]
                  --demo          offline synthetic headlines (uses ./cassandra-demo-data)
                  --data=DIR      directory for history.tsv and predictions.tsv (default ./cassandra-data)
                  --no-log        analyse only; do not read or write saved state
                  --asof=DATE     pretend today is DATE (yyyy-mm-dd); demos and backtests only
                  --report        print an accuracy report from saved predictions (no feeds are fetched)
                  --gui           open the desktop window (use with --data=DIR or --demo to pick the data)
                """);
    }

    // ------------------------------------------------------------------ fetching

    static List<Article> fetchFeed(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Cassandra/1.0 (educational project)")
                .GET().build();
        HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) throw new RuntimeException("HTTP " + resp.statusCode());

        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); // block XXE
        Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(resp.body()));

        String host = URI.create(url).getHost().replaceFirst("^(www|feeds)\\.", "");
        List<Article> out = new ArrayList<>();
        NodeList items = doc.getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            String title = clean(childText(item, "title"));
            String body = clean(childText(item, "description"));
            if (title.isBlank()) continue;
            out.add(new Article(host, title, body, parseDate(childText(item, "pubDate")),
                    childText(item, "link").trim()));
        }
        return out;
    }

    static String childText(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        return nl.getLength() == 0 ? "" : nl.item(0).getTextContent();
    }

    /** Feed boilerplate such as "Continue reading..." that would otherwise swamp the real headlines. */
    static final String BOILERPLATE = "(?i)\\b(continue|keep)\\s+reading\\b[.\\u2026\\s]*"
            + "|(?i)\\bread\\s+(more|the full (story|article))\\b[.\\u2026\\s]*";

    static String clean(String s) {
        return s.replaceAll("<[^>]*>", " ").replaceAll("&[a-z#0-9]+;", " ")
                .replaceAll(BOILERPLATE, " ").replaceAll("\\s+", " ").trim();
    }

    static Instant parseDate(String s) {
        try {
            return ZonedDateTime.parse(s.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (Exception e) {
            return NOW;
        }
    }

    static List<Article> dedupe(List<Article> in) {
        Set<String> seen = new HashSet<>();
        List<Article> out = new ArrayList<>();
        for (Article a : in) if (seen.add(a.title().toLowerCase())) out.add(a);
        return out;
    }

    // ------------------------------------------------------------------ text analysis

    static String stem(String w) {
        return (w.length() > 4 && w.endsWith("s") && !w.endsWith("ss")) ? w.substring(0, w.length() - 1) : w;
    }

    static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        for (String raw : text.toLowerCase().split("[^a-z]+")) {
            if (raw.length() < 2 || STOP.contains(raw)) continue;
            String s = stem(raw);
            FORMS.computeIfAbsent(s, k -> new HashMap<>()).merge(raw, 1, Integer::sum);
            out.add(s);
        }
        return out;
    }

    static String surface(String stem) {
        Map<String, Integer> m = FORMS.get(stem);
        if (m == null) return stem;
        return Collections.max(m.entrySet(), Map.Entry.comparingByValue()).getKey();
    }

    static Doc analyse(Article a) {
        List<String> tokens = tokenize(a.title() + " " + a.body());
        Set<String> titleTerms = new HashSet<>(tokenize(a.title()));
        return new Doc(a, tokens, new HashSet<>(tokens), titleTerms);
    }

    static int netSentiment(Doc d) {
        int n = 0;
        for (String t : d.tokens()) {
            if (POS.contains(t)) n++;
            if (NEG.contains(t)) n--;
        }
        return n;
    }

    static double overlap(List<Doc> a, List<Doc> b) {
        Set<Doc> bs = Collections.newSetFromMap(new IdentityHashMap<>());
        bs.addAll(b);
        long common = a.stream().filter(bs::contains).count();
        return (double) common / Math.min(a.size(), b.size());
    }

    static Domain classify(List<Doc> story) {
        Domain best = Domain.GENERAL;
        int bestHits = 1; // need at least 2 hits to leave GENERAL
        for (var e : LEX.entrySet()) {
            int hits = 0;
            for (Doc d : story) for (String t : d.tokens()) if (e.getValue().contains(t)) hits++;
            if (hits > bestHits) { bestHits = hits; best = e.getKey(); }
        }
        return best;
    }

    static String momentum(List<Doc> story) {
        long recent = story.stream().filter(d -> age(d) <= 8).count();
        long earlier = story.stream().filter(d -> age(d) > 8 && age(d) <= 32).count();
        if (recent + earlier < 4) return "unclear";
        double recentRate = recent / 8.0, earlierRate = earlier / 24.0;
        if (earlierRate == 0) return "accelerating";
        double ratio = recentRate / earlierRate;
        return ratio > 1.3 ? "accelerating" : ratio < 0.7 ? "fading" : "steady";
    }

    static double age(Doc d) {
        return Duration.between(d.article().published(), NOW).toMinutes() / 60.0;
    }

    static String bestPhrase(String term, List<Doc> docs) {
        Map<String, Integer> counts = new HashMap<>();
        for (Doc d : docs) {
            List<String> t = d.tokens();
            for (int i = 0; i + 1 < t.size(); i++) {
                if (t.get(i).equals(term) || t.get(i + 1).equals(term))
                    counts.merge(t.get(i) + " " + t.get(i + 1), 1, Integer::sum);
            }
        }
        var best = counts.entrySet().stream().max(Map.Entry.comparingByValue());
        if (best.isPresent() && best.get().getValue() >= 2) {
            String[] p = best.get().getKey().split(" ");
            return surface(p[0]) + " " + surface(p[1]);
        }
        return surface(term);
    }

    static List<String> relatedTerms(String topTerm, List<Doc> story, int n) {
        Map<String, Integer> counts = new HashMap<>();
        for (Doc d : story) for (String t : d.terms()) if (!t.equals(topTerm)) counts.merge(t, 1, Integer::sum);
        return counts.entrySet().stream()
                .filter(e -> e.getValue() >= 2 && e.getKey().length() > 3)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(n).map(e -> surface(e.getKey())).toList();
    }

    /** Collapse near-duplicate terms ("port", "strike") into one story each, best first. */
    static List<String> storyReps(List<String> ranked, Map<String, List<Doc>> index, int max) {
        List<String> reps = new ArrayList<>();
        for (String t : ranked) {
            boolean dup = false;
            for (String r : reps) if (overlap(index.get(t), index.get(r)) > 0.6) { dup = true; break; }
            if (!dup) reps.add(t);
            if (reps.size() >= max) break;
        }
        return reps;
    }

    // ------------------------------------------------------------------ the forecaster

    /** Another storyline worth watching: its readable label and how many articles mention it. */
    record Other(String label, int articles) {}

    /** The scenario narrative: one text per horizon (days, weeks, months), the alternative branch, a confidence label. */
    record Scenario(List<String> steps, String alternative, String confidence) {}

    /** Everything one forecasting run found and did. The terminal printer and the GUI only read this. */
    record RunResult(LocalDate date, int headlines, List<Story> top, Domain domain, long sources,
                     List<String> related, boolean anyPriorRuns, List<Pred> fresh, int added, boolean logged,
                     List<Pred> gradedNow, Map<String, Stat> record, List<Pred> preds,
                     List<Article> samples, List<Other> others, Scenario scenario) {
        Story lead() { return top.get(0); }
    }

    static void report(List<Article> articles, Path dir, boolean log) throws IOException {
        RunResult r = forecastRun(articles, dir, log);
        if (r == null) {
            System.out.println("No topic appears in enough articles to call it a trend.");
            return;
        }
        printRun(r);
    }

    /**
     * One forecasting run: rank today's stories, grade predictions that have fallen due, measure the
     * leading stories, issue new predictions and (unless log is false) save everything.
     * Returns null when no story has enough coverage to call it a trend.
     */
    static RunResult forecastRun(List<Article> articles, Path dir, boolean log) throws IOException {
        List<Doc> docs = articles.stream().map(Cassandra::analyse).toList();

        // 1. Index and rank terms
        Map<String, List<Doc>> index = new HashMap<>();
        for (Doc d : docs) for (String t : d.terms()) index.computeIfAbsent(t, k -> new ArrayList<>()).add(d);

        Map<String, Double> scores = new HashMap<>();
        for (var e : index.entrySet()) {
            List<Doc> ds = e.getValue();
            if (ds.size() < 3) continue;
            long sources = ds.stream().map(d -> d.article().source()).distinct().count();
            long titleHits = ds.stream().filter(d -> d.titleTerms().contains(e.getKey())).count();
            scores.put(e.getKey(), ds.size() + 1.5 * (sources - 1) + titleHits);
        }
        if (scores.isEmpty()) return null;
        List<String> ranked = scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey).toList();
        List<String> reps = storyReps(ranked, index, 10);

        Snapshot snap = new Snapshot(index, reps, docs.size());

        // 2. Saved state: history and predictions
        List<HistRow> history = log ? loadHistory(dir) : new ArrayList<>();
        List<Pred> preds = log ? loadPreds(dir) : new ArrayList<>();
        boolean anyPriorRuns = history.stream().anyMatch(r -> r.date().isBefore(TODAY));

        // 3. Grade anything that has fallen due
        List<Pred> gradedNow = new ArrayList<>();
        for (int i = 0; i < preds.size(); i++) {
            Pred p = preds.get(i);
            if (!p.outcome().equals("PENDING")) continue;
            Pred r = resolve(p, snap);
            if (r != p) {
                preds.set(i, r);
                if (r.graded()) gradedNow.add(r);
            }
        }
        Map<String, Stat> record = trackRecord(preds);

        // 4. Measure the leading stories, including a trend fit for each
        List<Story> top = new ArrayList<>();
        for (int i = 0; i < Math.min(PREDICTED_STORIES, reps.size()); i++)
            top.add(describe(i + 1, reps.get(i), snap, history));

        Story lead = top.get(0);
        List<Doc> story = lead.docs();
        String label = lead.label();
        List<String> related = relatedTerms(lead.term(), story, 8).stream()
                .filter(w -> !label.contains(w)).limit(3).toList();
        long sources = story.stream().map(d -> d.article().source()).distinct().count();
        Domain domain = classify(story);

        // 5. Issue new predictions for each leading story
        List<Pred> fresh = new ArrayList<>();
        for (Story s : top) fresh.addAll(makePredictions(s, history, record));
        Set<String> known = preds.stream().map(Pred::id).collect(Collectors.toSet());
        List<Pred> toAdd = fresh.stream().filter(p -> !known.contains(p.id())).toList();
        if (log) {
            preds.addAll(toAdd);
            saveState(dir, history, preds, reps, snap, lead.term());
        }

        // 6. What else the reader needs
        List<Article> samples = story.stream()
                .sorted(Comparator.comparing((Doc d) -> d.article().published()).reversed())
                .limit(4).map(Doc::article).toList();
        List<Other> others = new ArrayList<>();
        for (int i = 1; i < Math.min(4, reps.size()); i++)
            others.add(new Other(bestPhrase(reps.get(i), index.get(reps.get(i))), index.get(reps.get(i)).size()));

        RunResult result = new RunResult(TODAY, docs.size(), top, domain, sources, related, anyPriorRuns, fresh,
                toAdd.size(), log, gradedNow, record, preds, samples, others, scenario(lead, domain, related, sources));
        if (log) saveLatest(dir, result);
        return result;
    }

    /** The template narrative for the dominant story. */
    static Scenario scenario(Story lead, Domain domain, List<String> related, long sources) {
        String label = lead.label();
        Tone tone = lead.tone();
        String ctx = related.isEmpty() ? "" : " (touching " + joinNatural(related) + ")";
        String[][] chain = chain(domain);
        int primary = tone == Tone.NEGATIVE ? 0 : tone == Tone.POSITIVE ? 1 : (lead.net() <= 0 ? 0 : 1);
        int alt = 1 - primary;

        List<String> steps = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String text = fill(chain[i][primary], label, ctx);
            if (i == 0) text += " " + momentumSentence(lead.momentum());
            steps.add(text);
        }
        String alternative = fill(chain[1][alt], label, ctx) + " " + fill(chain[2][alt], label, ctx);

        double conf = Math.min(1, lead.docs().size() / 15.0) * 0.5 + Math.min(1, sources / 4.0) * 0.3
                + (tone == Tone.MIXED ? 0.05 : 0.2);
        String confidence = conf < 0.4 ? "LOW" : conf < 0.7 ? "MODERATE" : "FAIRLY HIGH (for a heuristic)";
        return new Scenario(steps, alternative, confidence);
    }

    /** Print a run to the terminal. Reads the result only; computes nothing. */
    static void printRun(RunResult r) {
        Story lead = r.lead();
        Forecast fc = lead.fc();
        String bar = "=".repeat(WRAP);
        System.out.println(bar);
        System.out.println(" CASSANDRA - forecast for " + r.date() + ", from " + r.headlines() + " headlines");
        System.out.println(bar);

        System.out.println("\nDOMINANT STORY: \"" + lead.label() + "\"");
        System.out.println(wrap(String.format(Locale.ROOT,
                "Mentioned in %d of %d articles (%.0f%% of coverage) across %d outlet(s). Domain: %s. "
                        + "Tone: %s (net sentiment %+.2f per article). Momentum today: %s.%s",
                lead.docs().size(), r.headlines(), lead.share() * 100, r.sources(), r.domain().name().toLowerCase(),
                lead.tone().name().toLowerCase(), lead.net(), lead.momentum(),
                r.related().isEmpty() ? "" : " Frequently linked with: " + String.join(", ", r.related()) + "."), ""));

        System.out.println("\nSAMPLE HEADLINES");
        for (Article a : r.samples()) System.out.println(wrap("- " + a.title() + " [" + a.source() + "]", "  "));

        // coverage forecast
        System.out.println("\nCOVERAGE FORECAST (share of headlines mentioning this story)");
        if (fc == null) {
            String why;
            if (!r.anyPriorRuns())
                why = "Not enough history yet to fit a trend (need observations on 3+ different days). "
                        + "Run Cassandra daily and this section will fill in.";
            else if (lead.firstSeen() == null)
                why = "New story: it was not in the top 10 on any earlier run, so there is no trend to fit yet. "
                        + "A trend needs 3+ days of data from the day a story first appears; until then the "
                        + "SHARE_UP forecast falls back on today's momentum.";
            else
                why = String.format(Locale.ROOT, "Young story: first in the top 10 on %s, which gives %d day(s) "
                        + "of data including today. A trend needs 3.", lead.firstSeen(), lead.points());
            System.out.println(wrap(why, "  "));
        } else {
            System.out.println(wrap(String.format(Locale.ROOT,
                    "Trend over %d days of data (since %s): %+.1f percentage points per day. Today %.0f%%.",
                    fc.n(), lead.firstSeen(), fc.slope() * 100, lead.share() * 100), "  "));
            for (int h : new int[]{3, 7}) {
                double mid = clamp(fc.at(h), 0, 1), w = 1.2816 * fc.predSd(h);
                System.out.println(String.format(Locale.ROOT, "  In %d days: about %.0f%% (80%% range %.0f%%-%.0f%%)",
                        h, mid * 100, clamp(mid - w, 0, 1) * 100, clamp(mid + w, 0, 1) * 100));
            }
        }

        // predictions
        List<Story> top = r.top();
        System.out.println("\nPREDICTIONS FOR THE TOP " + top.size() + (top.size() == 1 ? " STORY" : " STORIES")
                + " (logged and graded when due)");
        for (Story s : top) {
            System.out.println(String.format(Locale.ROOT, "  %d. \"%s\" - %.0f%% of coverage%s", s.rank(), s.label(),
                    s.share() * 100, r.anyPriorRuns() && s.firstSeen() == null ? ", new today" : ""));
            for (Pred p : r.fresh())
                if (p.term().equals(s.term()))
                    System.out.println(wrap("- " + statement(p) + "  -> " + pct(p.p())
                            + (p.p() != p.pRaw() ? " [recalibrated from " + pct(p.pRaw()) + "]" : ""), "     ", "       "));
        }
        if (!r.logged()) System.out.println("  (--no-log: these were not saved and cannot be graded later)");
        else if (r.added() == 0) System.out.println("  (already logged earlier today)");

        // scenario
        System.out.println("\nMOST LIKELY SEQUENCE OF EVENTS");
        String[] horizons = {"Next few days", "Coming weeks", "Coming months"};
        for (int i = 0; i < 3; i++) {
            System.out.println(wrap(horizons[i] + ": " + r.scenario().steps().get(i), "  "));
            System.out.println();
        }
        System.out.println("ALTERNATIVE BRANCH");
        System.out.println(wrap("If the early signs above do not hold: " + r.scenario().alternative(), "  "));
        System.out.println("\nSCENARIO CONFIDENCE: " + r.scenario().confidence());

        // graded this run + track record
        if (!r.gradedNow().isEmpty()) {
            System.out.println("\nGRADED TODAY");
            for (Pred p : r.gradedNow())
                System.out.println(wrap("- [" + (p.outcome().equals("TRUE") ? "CAME TRUE" : "DID NOT HAPPEN") + "] "
                        + statement(p) + " (forecast " + pct(p.p()) + ")", "  "));
        }
        printTrackRecord(r.record(), r.preds(), r.logged());

        System.out.println("\nOTHER STORYLINES TO WATCH");
        for (Other o : r.others()) System.out.println(wrap("- " + o.label() + " (" + o.articles() + " articles)", "  "));

        System.out.println("\nNOTE: Cassandra cannot see the future. Her probabilities start as educated guesses and are "
                + "only as good as her track record shows. Do not use her output for financial, political or "
                + "safety decisions.");
    }

    // ------------------------------------------------------------------ predictions

    /** Measure one of today's leading stories: label, tone, momentum, share and trend. */
    static Story describe(int rank, String term, Snapshot snap, List<HistRow> history) {
        List<Doc> ds = snap.index.get(term);
        double net = ds.stream().mapToInt(Cassandra::netSentiment).average().orElse(0);
        Tone tone = net <= -0.5 ? Tone.NEGATIVE : net >= 0.5 ? Tone.POSITIVE : Tone.MIXED;
        double share = snap.share(term);
        List<double[]> pts = trendPoints(history, term, share);
        return new Story(rank, term, ds, bestPhrase(term, ds), net, tone, momentum(ds), share,
                firstSeen(history, term), pts.size(), fit(pts));
    }

    static List<Pred> makePredictions(Story s, List<HistRow> history, Map<String, Stat> record) {
        List<Pred> out = new ArrayList<>();
        String term = s.term(), label = s.label(), momentum = s.momentum();
        Tone tone = s.tone();
        double net = s.net(), shareNow = s.share();
        Forecast fc = s.fc();

        // persistence: how many consecutive earlier days was this story already in the top 5?
        int streak = 0;
        List<LocalDate> dates = history.stream().map(HistRow::date).filter(d -> d.isBefore(TODAY))
                .distinct().sorted(Comparator.reverseOrder()).toList();
        for (LocalDate d : dates) {
            boolean in = history.stream().anyMatch(r -> r.date().equals(d) && r.term().equals(term) && r.rank() <= 5);
            if (in) streak++; else break;
        }
        double mom = momentum.equals("accelerating") ? 0.10 : momentum.equals("fading") ? -0.15 : 0;
        // lower-ranked stories are less secure in the top 5, so their prior starts lower
        double top3 = clamp(0.62 - RANK_PENALTY * (s.rank() - 1) + 0.05 * Math.min(streak, 5) + mom, 0.10, 0.92);
        double top7 = clamp(top3 - 0.15, 0.10, 0.92);

        out.add(pred(term, label, "TOP5", 3, "-", top3, record));
        out.add(pred(term, label, "TOP5", 7, "-", top7, record));

        // will its share of coverage be higher in 3 days than today?
        double up;
        if (fc != null) up = phi(fc.slope() * 3 / fc.predSd(3));
        else up = momentum.equals("accelerating") ? 0.55 : momentum.equals("fading") ? 0.30 : 0.42;
        out.add(pred(term, label, "SHARE_UP", 3, String.format(Locale.ROOT, "%.4f", shareNow),
                clamp(up, 0.05, 0.95), record));

        // will the tone persist?
        if (tone != Tone.MIXED) {
            double a = Math.abs(net);
            double pt = a >= 1.5 ? 0.72 : a >= 1.0 ? 0.66 : 0.58;
            out.add(pred(term, label, "TONE_PERSIST", 3, tone == Tone.NEGATIVE ? "NEG" : "POS", pt, record));
        }
        return out;
    }

    static Pred pred(String term, String label, String kind, int days, String param, double pRaw,
                     Map<String, Stat> record) {
        LocalDate due = TODAY.plusDays(days);
        double p = calibrate(pRaw, record.get(kind + "@" + days));
        String id = TODAY + "-" + kind + days + "-" + term;
        return new Pred(id, TODAY, due, term, label.replace('\t', ' '), kind, param, pRaw, p, "PENDING", "-");
    }

    static String statement(Pred p) {
        return switch (p.kind()) {
            case "TOP5" -> "By " + p.due() + ", \"" + p.label() + "\" will still be among the top 5 stories.";
            case "SHARE_UP" -> "By " + p.due() + ", \"" + p.label() + "\" will take a larger share of headlines than on "
                    + p.made() + " (" + String.format(Locale.ROOT, "%.0f%%", Double.parseDouble(p.param()) * 100) + ").";
            case "TONE_PERSIST" -> "On " + p.due() + ", coverage of \"" + p.label() + "\" will still be clearly "
                    + (p.param().equals("NEG") ? "negative" : "positive") + " in tone.";
            default -> p.kind() + " " + p.label();
        };
    }

    /** Grade a prediction if it is due. Returns the same object if it is not yet due. */
    static Pred resolve(Pred p, Snapshot s) {
        if (TODAY.isBefore(p.due())) return p;
        if (TODAY.isAfter(p.due().plusDays(GRACE_DAYS))) return p.with("VOID", TODAY.toString());
        switch (p.kind()) {
            case "TOP5":
                return p.with(String.valueOf(s.inTop5(p.term())).toUpperCase(), TODAY.toString());
            case "SHARE_UP":
                return p.with(String.valueOf(s.share(p.term()) > Double.parseDouble(p.param())).toUpperCase(),
                        TODAY.toString());
            case "TONE_PERSIST": {
                OptionalDouble n = s.net(p.term());
                if (n.isEmpty()) return p.with("VOID", TODAY.toString());
                boolean hit = p.param().equals("NEG") ? n.getAsDouble() <= -0.5 : n.getAsDouble() >= 0.5;
                return p.with(String.valueOf(hit).toUpperCase(), TODAY.toString());
            }
            default:
                return p.with("VOID", TODAY.toString());
        }
    }

    // ------------------------------------------------------------------ calibration and scoring

    static Map<String, Stat> trackRecord(List<Pred> preds) {
        Map<String, List<Pred>> by = new TreeMap<>();
        for (Pred p : preds) if (p.graded()) by.computeIfAbsent(p.key(), k -> new ArrayList<>()).add(p);
        Map<String, Stat> out = new TreeMap<>();
        for (var e : by.entrySet()) out.put(e.getKey(), stat(e.getValue()));
        return out;
    }

    static Stat stat(List<Pred> ps) {
        int hits = 0;
        double raw = 0, brier = 0;
        for (Pred p : ps) {
            double y = p.outcome().equals("TRUE") ? 1 : 0;
            hits += (int) y;
            raw += p.pRaw();
            brier += (p.p() - y) * (p.p() - y);
        }
        return new Stat(ps.size(), hits, raw / ps.size(), brier / ps.size());
    }

    /** Shift a probability in logit space by this prediction type's historical bias. */
    static double calibrate(double p, Stat s) {
        if (s == null || s.n() < MIN_FOR_CALIBRATION) return p;
        double rate = clamp((s.hits() + 0.5) / (s.n() + 1.0), 0.02, 0.98);
        double mean = clamp(s.meanRaw(), 0.02, 0.98);
        double w = s.n() / (s.n() + 10.0);
        return clamp(sigmoid(logit(p) + w * (logit(rate) - logit(mean))), 0.03, 0.97);
    }

    static void printTrackRecord(Map<String, Stat> record, List<Pred> preds, boolean log) {
        System.out.println("\nCASSANDRA'S TRACK RECORD");
        long pending = preds.stream().filter(p -> p.outcome().equals("PENDING")).count();
        if (!log) { System.out.println("  (--no-log: no saved state)"); return; }
        if (record.isEmpty()) {
            System.out.println(wrap("No predictions have been graded yet. Predictions fall due 3 and 7 days after they "
                    + "are made, so run Cassandra daily and a record will build up. Pending: " + pending + ".", "  "));
            return;
        }
        System.out.println("  type             graded  came true  avg forecast  Brier (lower is better)");
        int n = 0;
        double sumB = 0;
        for (var e : record.entrySet()) {
            Stat s = e.getValue();
            System.out.println(String.format(Locale.ROOT, "  %-16s %6d  %8.0f%%  %11.0f%%  %.3f",
                    e.getKey(), s.n(), 100.0 * s.hits() / s.n(), 100 * s.meanRaw(), s.brier()));
            n += s.n();
            sumB += s.brier() * s.n();
        }
        double overall = sumB / n;
        System.out.println(wrap(String.format(Locale.ROOT,
                "Overall Brier score %.3f over %d graded predictions (a coin-flip forecaster scores 0.250; "
                        + "a perfect one 0.000). %s Pending: %d.", overall, n,
                overall < 0.25 ? "Cassandra is currently beating a coin flip."
                        : "Cassandra is NOT currently beating a coin flip - treat her forecasts accordingly.",
                pending), "  "));
        List<Pred> graded = preds.stream().filter(Pred::graded).toList();
        Map<String, Double> base = baseRates(graded);
        List<Pred> comp = graded.stream().filter(p -> base.containsKey(p.id())).toList();
        double ref = brier(comp, p -> base.get(p.id()));
        if (comp.size() >= MIN_FOR_VERDICT && ref > 0)
            System.out.println(wrap(String.format(Locale.ROOT, "Skill against a base-rate forecaster: %+.2f over %d "
                    + "comparable predictions (above 0 means she adds something beyond \"what usually happens\"). "
                    + "Run with --report for the full picture.", 1 - brier(comp, Pred::p) / ref, comp.size()), "  "));
        else
            System.out.println(wrap("Too early to compare her with a base-rate forecaster (" + comp.size() + " of "
                    + MIN_FOR_VERDICT + " comparable predictions). Run with --report for details.", "  "));
        System.out.println(wrap("Types with " + MIN_FOR_CALIBRATION + "+ graded predictions have their probabilities "
                + "automatically recalibrated.", "  "));
    }

    // ------------------------------------------------------------------ accuracy report (--report)

    static final int BOOTSTRAP = 2000;     // resamples for the skill-score interval
    static final int BASE_WARMUP = 5;      // earlier results of a type the base-rate forecaster needs
    static final int MIN_FOR_VERDICT = 30; // comparable predictions needed before --report gives a verdict

    static double outcome(Pred p) { return p.outcome().equals("TRUE") ? 1 : 0; }

    static LocalDate resolvedOn(Pred p) {
        try { return LocalDate.parse(p.resolved()); } catch (RuntimeException e) { return p.due(); }
    }

    static double brier(List<Pred> ps, java.util.function.ToDoubleFunction<Pred> forecast) {
        if (ps.isEmpty()) return Double.NaN;
        double s = 0;
        for (Pred p : ps) { double d = forecast.applyAsDouble(p) - outcome(p); s += d * d; }
        return s / ps.size();
    }

    /**
     * The benchmark forecaster: for each prediction it forecasts how often claims of the same type
     * (kind and horizon) had come true among those already graded when the prediction was made.
     * It only uses information that existed at the time, so it is a fair, beatable opponent.
     * Predictions made before BASE_WARMUP results of their type were known are left out of the map:
     * a benchmark with no history would just say 50%, and beating that proves nothing.
     */
    static Map<String, Double> baseRates(List<Pred> graded) {
        Map<String, Double> out = new HashMap<>();
        for (Pred p : graded) {
            int n = 0, hits = 0;
            for (Pred q : graded)
                if (q.key().equals(p.key()) && q != p && !resolvedOn(q).isAfter(p.made())) {
                    n++;
                    hits += (int) outcome(q);
                }
            if (n >= BASE_WARMUP) out.put(p.id(), (hits + 0.5) / (n + 1.0));
        }
        return out;
    }

    /**
     * 90% bootstrap interval for the skill score 1 - B(Cassandra) / B(base rate).
     * Whole days are resampled rather than single predictions: predictions made on the same day are
     * about the same stories and tend to succeed or fail together, and treating them as independent
     * would make the interval look narrower than it really is.
     */
    static double[] skillInterval(List<Pred> graded, Map<String, Double> base) {
        Map<LocalDate, double[]> byDay = new TreeMap<>(); // made date -> {sum Brier Cassandra, sum Brier base}
        for (Pred p : graded) {
            double[] s = byDay.computeIfAbsent(p.made(), k -> new double[2]);
            s[0] += Math.pow(p.p() - outcome(p), 2);
            s[1] += Math.pow(base.get(p.id()) - outcome(p), 2);
        }
        List<double[]> days = new ArrayList<>(byDay.values());
        int n = days.size();
        Random rnd = new Random(42);
        double[] skills = new double[BOOTSTRAP];
        for (int k = 0; k < BOOTSTRAP; k++) {
            double sb = 0, sr = 0;
            for (int i = 0; i < n; i++) { double[] d = days.get(rnd.nextInt(n)); sb += d[0]; sr += d[1]; }
            skills[k] = sr == 0 ? 0 : 1 - sb / sr;
        }
        Arrays.sort(skills);
        return new double[]{skills[(int) (0.05 * BOOTSTRAP)], skills[(int) (0.95 * BOOTSTRAP) - 1]};
    }

    static String num(double v, String fmt) { return Double.isNaN(v) ? "n/a" : String.format(Locale.ROOT, fmt, v); }

    enum Verdict { TOO_EARLY, BEATING, WORSE, UNCLEAR }

    /** One prediction type's scores. skill is NaN when no prediction of this type can be compared yet. */
    record TypeRow(String key, int n, double hitRate, double avgForecast, double brier, double skill, int comparable) {}

    /** One reliability band: forecasts from lo to hi, how many, their average, and how often they came true. */
    record Bin(double lo, double hi, int n, double avgForecast, double observed) {
        String verdict() {
            double gap = (observed - avgForecast) * 100;
            return n < 5 ? "too few to judge"
                    : Math.abs(gap) <= 10 ? "well calibrated"
                    : gap < 0 ? String.format(Locale.ROOT, "forecasts too high by %.0f pts", -gap)
                    : String.format(Locale.ROOT, "forecasts too low by %.0f pts", gap);
        }
    }

    /**
     * One week of grades (by date graded). brier/brierRaw: all graded that week. The comp* fields cover only
     * the predictions the base-rate forecaster could also make, so both face the same questions; NaN if none.
     */
    record WeekRow(LocalDate week, int n, double brier, double brierRaw,
                   int comparable, double compBrier, double compRaw, double compBase) {}

    /**
     * Everything the accuracy report shows. bCass/bRaw/bCoin cover all graded predictions; cCass/cBase only
     * the comparable ones. ci is the 90% interval on the skill score, or null while it is too early to judge.
     */
    record Accuracy(Path dir, int logged, int pending, int voided, List<Pred> graded, List<Pred> comparable,
                    double bCass, double bRaw, double bCoin, double cCass, double cBase, double skill, double[] ci,
                    Verdict verdict, int recalibrated, List<TypeRow> types, List<Bin> bins, List<WeekRow> weeks) {}

    /** Load and score a data directory's predictions, or return null if it has none. */
    static Accuracy loadAccuracy(Path dir) {
        if (!Files.exists(dir.resolve("predictions.tsv"))) return null;
        return computeAccuracy(loadPreds(dir), dir);
    }

    static Accuracy computeAccuracy(List<Pred> preds, Path dir) {
        List<Pred> graded = preds.stream().filter(Pred::graded)
                .sorted(Comparator.comparing(Cassandra::resolvedOn).thenComparing(Pred::id)).toList();
        int pending = (int) preds.stream().filter(p -> p.outcome().equals("PENDING")).count();
        int voided = (int) preds.stream().filter(p -> p.outcome().equals("VOID")).count();

        Map<String, Double> base = baseRates(graded);
        java.util.function.ToDoubleFunction<Pred> baseOf = p -> base.get(p.id());
        List<Pred> comp = graded.stream().filter(p -> base.containsKey(p.id())).toList();
        double bCass = brier(graded, Pred::p), bRaw = brier(graded, Pred::pRaw), bCoin = brier(graded, p -> 0.5);
        double cCass = brier(comp, Pred::p), cBase = brier(comp, baseOf);
        double skill = comp.isEmpty() || cBase == 0 ? Double.NaN : 1 - cCass / cBase;

        double[] ci = null;
        Verdict verdict = Verdict.TOO_EARLY;
        if (comp.size() >= MIN_FOR_VERDICT) {
            ci = skillInterval(comp, base);
            verdict = ci[0] > 0 ? Verdict.BEATING : ci[1] < 0 ? Verdict.WORSE : Verdict.UNCLEAR;
        }
        int recal = (int) graded.stream().filter(p -> Math.abs(p.p() - p.pRaw()) > 1e-9).count();

        List<TypeRow> types = new ArrayList<>();
        Map<String, List<Pred>> byType = new TreeMap<>();
        for (Pred p : graded) byType.computeIfAbsent(p.key(), k -> new ArrayList<>()).add(p);
        for (var e : byType.entrySet()) {
            List<Pred> ps = e.getValue();
            List<Pred> cs = ps.stream().filter(p -> base.containsKey(p.id())).toList();
            double cb = brier(cs, Pred::p), cr = brier(cs, baseOf);
            types.add(new TypeRow(e.getKey(), ps.size(), ps.stream().mapToDouble(Cassandra::outcome).average().orElse(0),
                    ps.stream().mapToDouble(Pred::p).average().orElse(0), brier(ps, Pred::p),
                    cs.isEmpty() || cr == 0 ? Double.NaN : 1 - cb / cr, cs.size()));
        }

        List<Bin> bins = new ArrayList<>();
        for (int k = 0; k < 5; k++) {
            int kk = k;
            List<Pred> bin = graded.stream().filter(p -> Math.min(4, (int) (p.p() * 5)) == kk).toList();
            bins.add(new Bin(k * 0.2, k * 0.2 + 0.2, bin.size(),
                    bin.stream().mapToDouble(Pred::p).average().orElse(0),
                    bin.stream().mapToDouble(Cassandra::outcome).average().orElse(0)));
        }

        List<WeekRow> weeks = new ArrayList<>();
        Map<LocalDate, List<Pred>> byWeek = new TreeMap<>();
        for (Pred p : graded) {
            LocalDate d = resolvedOn(p);
            byWeek.computeIfAbsent(d.minusDays(d.getDayOfWeek().getValue() - 1), k -> new ArrayList<>()).add(p);
        }
        for (var e : byWeek.entrySet()) {
            List<Pred> ps = e.getValue();
            List<Pred> cs = ps.stream().filter(p -> base.containsKey(p.id())).toList();
            weeks.add(new WeekRow(e.getKey(), ps.size(), brier(ps, Pred::p), brier(ps, Pred::pRaw),
                    cs.size(), brier(cs, Pred::p), brier(cs, Pred::pRaw), brier(cs, baseOf)));
        }
        return new Accuracy(dir, preds.size(), pending, voided, graded, comp, bCass, bRaw, bCoin, cCass, cBase,
                skill, ci, verdict, recal, types, bins, weeks);
    }

    static void accuracyReport(Path dir) {
        Accuracy a = loadAccuracy(dir);
        if (a == null) {
            System.out.println("No predictions found in " + dir.toAbsolutePath() + ". Run Cassandra a few times first "
                    + "(or pass --data=DIR / --demo to point at another data directory).");
            return;
        }
        printAccuracy(a);
    }

    /** Print the accuracy report to the terminal. Reads the result only; computes nothing. */
    static void printAccuracy(Accuracy a) {
        String bar = "=".repeat(WRAP);
        List<Pred> graded = a.graded();
        System.out.println(bar);
        System.out.println(" CASSANDRA - accuracy report");
        System.out.println(bar);
        System.out.println(wrap(String.format(Locale.ROOT, "Data: %s. %d predictions logged: %d graded, %d pending, "
                + "%d void (could not be graded, excluded from all scores).",
                a.dir(), a.logged(), graded.size(), a.pending(), a.voided()), ""));
        if (graded.isEmpty()) {
            System.out.println("\nNothing has been graded yet, so there is nothing to score. Predictions fall due 3 and "
                    + "7 days after they are made.");
            return;
        }
        System.out.println("Graded predictions were resolved between " + resolvedOn(graded.get(0)) + " and "
                + resolvedOn(graded.get(graded.size() - 1)) + ".");
        int nComp = a.comparable().size();

        // ---- headline
        System.out.println("\nTHE SHORT ANSWER");
        if (a.verdict() == Verdict.TOO_EARLY) {
            System.out.println(wrap(String.format(Locale.ROOT, "Too early to judge. Only %d prediction(s) can be "
                    + "compared with the base-rate forecaster so far; a verdict needs %d. (The benchmark needs %d "
                    + "earlier results of a type before it can make a forecast, so the first few of each type are "
                    + "skipped.)%s", nComp, MIN_FOR_VERDICT, BASE_WARMUP,
                    nComp == 0 ? "" : " Provisional skill score: " + num(a.skill(), "%+.2f") + "."), "  "));
        } else {
            String verdict = switch (a.verdict()) {
                case BEATING -> "Cassandra is beating the base-rate forecaster, by more than luck alone would easily "
                        + "explain: her probabilities carry information beyond \"what usually happens\".";
                case WORSE -> "Cassandra is doing WORSE than simply forecasting how often each type of claim usually "
                        + "comes true. Treat her probabilities with suspicion.";
                default -> "Cassandra cannot yet be told apart from the base-rate forecaster: any edge she has is "
                        + "within the range that luck alone could produce.";
            };
            System.out.println(wrap(verdict, "  "));
            System.out.println(wrap(String.format(Locale.ROOT, "Skill score %s over %d comparable predictions "
                    + "(90%% interval %+.2f to %+.2f). 0 means no better than the base rate, 1 would be perfect, "
                    + "below 0 is worse.", num(a.skill(), "%+.2f"), nComp, a.ci()[0], a.ci()[1]), "  "));
        }

        // ---- scores against benchmarks
        System.out.println("\nBRIER SCORES (lower is better; 0 is perfect)");
        System.out.println("  All " + graded.size() + " graded predictions:");
        System.out.println(String.format(Locale.ROOT, "    Cassandra (issued probabilities)    %.3f", a.bCass()));
        System.out.println(String.format(Locale.ROOT, "    Cassandra before recalibration      %.3f", a.bRaw()));
        System.out.println(String.format(Locale.ROOT, "    Coin flip (always 50%%)              %.3f", a.bCoin()));
        if (nComp > 0) {
            System.out.println("  The " + nComp + " predictions the base-rate forecaster could also make:");
            System.out.println(String.format(Locale.ROOT, "    Cassandra                           %.3f", a.cCass()));
            System.out.println(String.format(Locale.ROOT, "    Base-rate forecaster                %.3f", a.cBase()));
        }
        if (a.recalibrated() == 0)
            System.out.println(wrap("No graded prediction has been recalibrated yet, so the first two lines are the same.", "  "));
        else
            System.out.println(wrap(String.format(Locale.ROOT, "Recalibration changed %d of the %d graded predictions "
                    + "and %s her score (%.3f -> %.3f).", a.recalibrated(), graded.size(),
                    a.bCass() < a.bRaw() ? "improved" : a.bCass() > a.bRaw() ? "worsened" : "did not change",
                    a.bRaw(), a.bCass()), "  "));

        // ---- by type
        System.out.println("\nBY PREDICTION TYPE");
        System.out.println("  type             graded  came true  avg forecast   Brier   skill vs base rate");
        for (TypeRow t : a.types()) {
            String sk = Double.isNaN(t.skill()) ? "n/a"
                    : String.format(Locale.ROOT, "%+.2f (on %d)", t.skill(), t.comparable());
            System.out.println(String.format(Locale.ROOT, "  %-16s %6d  %8.0f%%  %11.0f%%   %.3f   %s",
                    t.key(), t.n(), 100 * t.hitRate(), 100 * t.avgForecast(), t.brier(), sk));
        }
        System.out.println(wrap("A type that comes true almost every time is easy to score well on; the skill column "
                + "shows whether Cassandra does better than simply knowing that.", "  "));

        // ---- reliability
        System.out.println("\nRELIABILITY (when she says X%, does it happen X% of the time?)");
        System.out.println("  forecast  graded  avg forecast  came true  verdict");
        for (Bin b : a.bins()) {
            String range = String.format(Locale.ROOT, "%-8s",
                    String.format(Locale.ROOT, "%.0f-%.0f%%", b.lo() * 100, b.hi() * 100));
            if (b.n() == 0) {
                System.out.println(String.format(Locale.ROOT, "  %s  %6d", range, 0));
                continue;
            }
            System.out.println(String.format(Locale.ROOT, "  %s  %6d  %11.0f%%  %8.0f%%  %s",
                    range, b.n(), b.avgForecast() * 100, b.observed() * 100, b.verdict()));
        }

        // ---- over time
        System.out.println("\nBRIER SCORE BY WEEK (week starting, by date graded)");
        System.out.println("  week of      graded  Cassandra  before recal  Cassandra vs base rate");
        List<WeekRow> weeks = a.weeks();
        for (WeekRow w : weeks.subList(Math.max(0, weeks.size() - 12), weeks.size())) {
            String br = w.comparable() == 0 ? "n/a" : String.format(Locale.ROOT, "%.3f vs %.3f (on %d)",
                    w.compBrier(), w.compBase(), w.comparable());
            System.out.println(String.format(Locale.ROOT, "  %s  %6d  %9.3f  %12.3f  %s", w.week(), w.n(),
                    w.brier(), w.brierRaw(), br));
        }
        if (weeks.size() > 12) System.out.println("  (showing the last 12 weeks)");

        System.out.println("\n" + wrap("HOW TO READ THIS: the base-rate forecaster is the opponent that matters. For each "
                + "prediction it simply forecasts how often that type of claim had come true so far, using only "
                + "results known at the time. Beating a coin flip is easy when most claims are usually true; beating "
                + "the base rate means Cassandra's reading of the news is adding something.", ""));
    }

    // ------------------------------------------------------------------ trend model

    /** Earliest earlier run within the look-back window on which this story was in the top 10, or null. */
    static LocalDate firstSeen(List<HistRow> history, String term) {
        LocalDate from = TODAY.minusDays(HISTORY_DAYS);
        return history.stream()
                .filter(r -> r.term().equals(term) && r.date().isBefore(TODAY) && !r.date().isBefore(from))
                .map(HistRow::date).min(Comparator.naturalOrder()).orElse(null);
    }

    /**
     * Daily (days-from-today, share) observations for the trend fit, starting from the day the story first
     * appeared. Earlier days are left out: counting them as zero would make every new story look like a
     * steep riser. After it first appears, a day it drops out of the top 10 still counts as a negligible
     * share, because that drop is real.
     */
    static List<double[]> trendPoints(List<HistRow> history, String term, double shareNow) {
        List<double[]> pts = new ArrayList<>();
        LocalDate first = firstSeen(history, term);
        if (first != null) {
            List<LocalDate> dates = history.stream().map(HistRow::date)
                    .filter(d -> d.isBefore(TODAY) && !d.isBefore(first)).distinct().sorted().toList();
            for (LocalDate d : dates) {
                Optional<HistRow> row = history.stream()
                        .filter(r -> r.date().equals(d) && r.term().equals(term)).findFirst();
                pts.add(new double[]{ChronoUnit.DAYS.between(TODAY, d), row.map(HistRow::share).orElse(0.0)});
            }
        }
        pts.add(new double[]{0, shareNow});
        return pts;
    }

    /** Least-squares line through the trend points, or null with fewer than 3 days of data. */
    static Forecast fit(List<double[]> pts) {
        int n = pts.size();
        if (n < 3) return null;

        double xbar = pts.stream().mapToDouble(p -> p[0]).average().orElse(0);
        double ybar = pts.stream().mapToDouble(p -> p[1]).average().orElse(0);
        double sxx = 0, sxy = 0;
        for (double[] p : pts) { sxx += (p[0] - xbar) * (p[0] - xbar); sxy += (p[0] - xbar) * (p[1] - ybar); }
        if (sxx == 0) return null;
        double slope = sxy / sxx, a = ybar - slope * xbar;
        double sse = 0;
        for (double[] p : pts) { double e = p[1] - (a + slope * p[0]); sse += e * e; }
        double sd = Math.max(0.02, Math.sqrt(sse / (n - 2)));
        return new Forecast(n, slope, a, sd, xbar, sxx);
    }

    // ------------------------------------------------------------------ latest-run snapshot (read by the GUI)

    static final String LATEST = "latest-run.tsv";

    record SnapArticle(String source, String title, String link, Instant published) {}

    /** One of the leading stories as the latest run saw it. fc is null when there was no trend to fit. */
    record SnapStory(int rank, String term, String label, int articles, double share, double net, Tone tone,
                     String momentum, LocalDate firstSeen, int points, boolean isNew, Forecast fc,
                     List<SnapArticle> headlines) {}

    /** What the latest saved run found, so the GUI can show it without fetching or recomputing anything. */
    record Latest(LocalDate date, Instant ranAt, int headlines, Domain domain, long sources, List<String> related,
                  List<SnapStory> stories, List<String> predictionIds, Scenario scenario, List<Other> others) {}

    static String cell(String s) { return s == null ? "" : s.replaceAll("[\\t\\r\\n]+", " ").trim(); }

    /**
     * Save what this run found to latest-run.tsv, one tagged line per item (run, story, headline,
     * prediction, step, alternative, other). It is replaced on every run and nothing else reads it.
     */
    static void saveLatest(Path dir, RunResult r) throws IOException {
        List<String> out = new ArrayList<>();
        out.add(String.join("\t", "run", r.date().toString(), NOW.toString(), String.valueOf(r.headlines()),
                r.domain().name(), String.valueOf(r.sources()), String.join(",", r.related()),
                cell(r.scenario().confidence())));
        for (Story s : r.top()) {
            Forecast f = s.fc();
            out.add(String.join("\t", "story", String.valueOf(s.rank()), s.term(), cell(s.label()),
                    String.valueOf(s.docs().size()), Double.toString(s.share()), Double.toString(s.net()),
                    s.tone().name(), s.momentum(), s.firstSeen() == null ? "-" : s.firstSeen().toString(),
                    String.valueOf(s.points()), String.valueOf(r.anyPriorRuns() && s.firstSeen() == null),
                    f == null ? "-" : f.n() + "," + f.slope() + "," + f.fittedToday() + "," + f.sd() + ","
                            + f.xbar() + "," + f.sxx()));
            s.docs().stream().sorted(Comparator.comparing((Doc d) -> d.article().published()).reversed()).limit(5)
                    .forEach(d -> out.add(String.join("\t", "headline", String.valueOf(s.rank()),
                            cell(d.article().source()), cell(d.article().title()), cell(d.article().link()),
                            d.article().published().toString())));
        }
        for (Pred p : r.fresh()) out.add("prediction\t" + p.id());
        for (String step : r.scenario().steps()) out.add("step\t" + cell(step));
        out.add("alternative\t" + cell(r.scenario().alternative()));
        for (Other o : r.others()) out.add("other\t" + cell(o.label()) + "\t" + o.articles());
        writeAtomically(dir.resolve(LATEST), out);
    }

    /** Read latest-run.tsv, or return null if there is none or it cannot be read. */
    static Latest loadLatest(Path dir) {
        Path f = dir.resolve(LATEST);
        if (!Files.exists(f)) return null;
        try {
            String[] run = null;
            List<SnapStory> stories = new ArrayList<>();
            Map<Integer, List<SnapArticle>> heads = new HashMap<>();
            List<String> ids = new ArrayList<>(), steps = new ArrayList<>();
            List<Other> others = new ArrayList<>();
            String alternative = "";
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String[] c = line.split("\t", -1);
                switch (c[0]) {
                    case "run" -> run = c;
                    case "story" -> {
                        int rank = Integer.parseInt(c[1]);
                        Forecast fc = null;
                        if (!c[12].equals("-")) {
                            String[] v = c[12].split(",");
                            fc = new Forecast(Integer.parseInt(v[0]), Double.parseDouble(v[1]), Double.parseDouble(v[2]),
                                    Double.parseDouble(v[3]), Double.parseDouble(v[4]), Double.parseDouble(v[5]));
                        }
                        stories.add(new SnapStory(rank, c[2], c[3], Integer.parseInt(c[4]), Double.parseDouble(c[5]),
                                Double.parseDouble(c[6]), Tone.valueOf(c[7]), c[8],
                                c[9].equals("-") ? null : LocalDate.parse(c[9]), Integer.parseInt(c[10]),
                                Boolean.parseBoolean(c[11]), fc, heads.computeIfAbsent(rank, k -> new ArrayList<>())));
                    }
                    case "headline" -> heads.computeIfAbsent(Integer.parseInt(c[1]), k -> new ArrayList<>())
                            .add(new SnapArticle(c[2], c[3], c[4], Instant.parse(c[5])));
                    case "prediction" -> ids.add(c[1]);
                    case "step" -> steps.add(c[1]);
                    case "alternative" -> alternative = c[1];
                    case "other" -> others.add(new Other(c[1], Integer.parseInt(c[2])));
                    default -> { /* ignore lines from newer versions */ }
                }
            }
            if (run == null || stories.isEmpty()) return null;
            return new Latest(LocalDate.parse(run[1]), Instant.parse(run[2]), Integer.parseInt(run[3]),
                    Domain.valueOf(run[4]), Long.parseLong(run[5]),
                    run[6].isEmpty() ? List.of() : List.of(run[6].split(",")), stories, ids,
                    new Scenario(steps, alternative, run[7]), others);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ persistence

    static List<HistRow> loadHistory(Path dir) {
        List<HistRow> out = new ArrayList<>();
        Path f = dir.resolve("history.tsv");
        if (!Files.exists(f)) return out;
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String[] c = line.split("\t", -1);
                if (c.length < 6) continue;
                try {
                    out.add(new HistRow(LocalDate.parse(c[0]), c[1], Integer.parseInt(c[2]),
                            Integer.parseInt(c[3]), Integer.parseInt(c[4]), Double.parseDouble(c[5])));
                } catch (RuntimeException ignored) { /* skip malformed line */ }
            }
        } catch (IOException e) {
            System.out.println("Could not read " + f + ": " + e.getMessage());
        }
        return out;
    }

    static List<Pred> loadPreds(Path dir) {
        List<Pred> out = new ArrayList<>();
        Path f = dir.resolve("predictions.tsv");
        if (!Files.exists(f)) return out;
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String[] c = line.split("\t", -1);
                if (c.length < 11) continue;
                try {
                    out.add(new Pred(c[0], LocalDate.parse(c[1]), LocalDate.parse(c[2]), c[3], c[4], c[5], c[6],
                            Double.parseDouble(c[7]), Double.parseDouble(c[8]), c[9], c[10]));
                } catch (RuntimeException ignored) { /* skip malformed line */ }
            }
        } catch (IOException e) {
            System.out.println("Could not read " + f + ": " + e.getMessage());
        }
        return out;
    }

    static void saveState(Path dir, List<HistRow> history, List<Pred> preds, List<String> reps,
                          Snapshot snap, String topTerm) throws IOException {
        Files.createDirectories(dir);

        // replace today's rows (so re-running on the same day does not duplicate them)
        history.removeIf(r -> r.date().equals(TODAY));
        for (int i = 0; i < reps.size(); i++) {
            String t = reps.get(i);
            List<Doc> ds = snap.index.get(t);
            double net = ds.stream().mapToInt(Cassandra::netSentiment).average().orElse(0);
            history.add(new HistRow(TODAY, t, i + 1, ds.size(), snap.total, net));
        }
        history.sort(Comparator.comparing(HistRow::date).thenComparingInt(HistRow::rank));
        List<String> h = new ArrayList<>();
        for (HistRow r : history)
            h.add(String.join("\t", r.date().toString(), r.term(), String.valueOf(r.rank()),
                    String.valueOf(r.count()), String.valueOf(r.total()),
                    String.format(Locale.ROOT, "%.4f", r.net())));
        writeAtomically(dir.resolve("history.tsv"), h);

        List<String> p = new ArrayList<>();
        for (Pred x : preds)
            p.add(String.join("\t", x.id(), x.made().toString(), x.due().toString(), x.term(), x.label(), x.kind(),
                    x.param(), String.format(Locale.ROOT, "%.4f", x.pRaw()),
                    String.format(Locale.ROOT, "%.4f", x.p()), x.outcome(), x.resolved()));
        writeAtomically(dir.resolve("predictions.tsv"), p);
    }

    /**
     * Write to a temporary file beside the target, then rename it into place. Anything reading the file
     * (such as the GUI, while a scheduled run is saving) sees either the old version or the new one,
     * never a half-written file.
     */
    static void writeAtomically(Path target, List<String> lines) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, lines, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ------------------------------------------------------------------ maths

    static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    static double logit(double p) { return Math.log(p / (1 - p)); }

    static double sigmoid(double x) { return 1 / (1 + Math.exp(-x)); }

    static double phi(double z) { return 0.5 * (1 + erf(z / Math.sqrt(2))); }

    /** Abramowitz and Stegun 7.1.26, max error about 1.5e-7. */
    static double erf(double x) {
        double t = 1 / (1 + 0.3275911 * Math.abs(x));
        double y = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t
                + 0.254829592) * t * Math.exp(-x * x);
        return x >= 0 ? y : -y;
    }

    static String pct(double p) { return String.format(Locale.ROOT, "%.0f%%", p * 100); }

    // ------------------------------------------------------------------ narrative templates

    static String momentumSentence(String m) {
        return switch (m) {
            case "accelerating" -> "Because coverage is accelerating, developments are likely to arrive faster than usual.";
            case "fading" -> "Because coverage is fading, the story may slip from the headlines unless a new development revives it.";
            case "steady" -> "Coverage is steady, so expect a gradual rather than abrupt progression.";
            default -> "There is too little timing data to say whether the pace will quicken or slow.";
        };
    }

    /** Each row = one time horizon; column 0 = negative-leaning, column 1 = positive-leaning. */
    static String[][] chain(Domain d) {
        return switch (d) {
            case CONFLICT -> new String[][]{
                    {"Further escalation or retaliation around {t}{c} is the most likely near-term path, with diplomatic channels strained and civilian impact dominating coverage.",
                     "Talks, pauses or de-escalation signals around {t}{c} are the most likely near-term path, with mediators gaining visibility."},
                    {"Security responses, sanctions and emergency aid decisions spread to neighbouring states and allies, pushing up energy and shipping costs.",
                     "Fragile agreements are tested by incidents, yet humanitarian access improves and reconstruction pledges begin to take shape."},
                    {"A prolonged standoff hardens alliances, raises defence spending and leaves lasting displacement and political fallout.",
                     "A settlement, if it holds, shifts attention to monitoring, rebuilding and domestic political reckonings."}};
            case ECONOMY -> new String[][]{
                    {"Markets react nervously to {t}{c}: volatility rises and businesses delay spending and hiring decisions.",
                     "Markets react positively to {t}{c}: confidence improves and firms signal plans to invest and hire."},
                    {"Higher costs feed through to consumer prices and borrowing; policymakers face pressure to respond, and weaker sectors begin to report losses.",
                     "Easing costs and stronger demand feed through to consumers; policymakers gain room to ease conditions and healthy sectors report gains."},
                    {"If the weakness persists, slower growth and job losses invite fresh policy intervention and political blame.",
                     "If the momentum persists, growth firms up, though it may bring new worries about overheating and uneven gains."}};
            case TECH -> new String[][]{
                    {"Concerns about {t}{c} intensify: expect calls for tougher rules, security reviews and public scrutiny of the firms involved.",
                     "Interest in {t}{c} surges: expect product launches, partnerships and heavy investment from competing firms."},
                    {"Regulators and rivals respond; companies adjust products, and users begin to push back or switch where trust is damaged.",
                     "Rivals race to match; adoption widens, and early problems around cost, safety and jobs start to appear."},
                    {"New rules and standards harden into a slower, more cautious industry, with trust becoming a competitive advantage.",
                     "The technology becomes embedded in everyday services, prompting debate on regulation, skills and who captures the value."}};
            case POLITICS -> new String[][]{
                    {"Controversy around {t}{c} deepens: expect sharper rhetoric, internal party strain and demands for accountability.",
                     "Momentum builds behind {t}{c}: expect coalition-building, policy announcements and favourable polling."},
                    {"Opponents mobilise, legal or procedural challenges appear, and leaders are forced to concede ground or shift position.",
                     "Opponents regroup, concessions are traded for support, and legislation or agreements move forward."},
                    {"Lasting realignment: loyalties fracture, and the issue shapes the next electoral contest.",
                     "The outcome is consolidated into policy, but its real-world effects become the next test of public support."}};
            case CLIMATE -> new String[][]{
                    {"Severe conditions linked to {t}{c} cause disruption: emergency responses, travel and supply problems, and rising damage estimates.",
                     "Positive developments around {t}{c} attract attention: new commitments, projects and supportive public opinion."},
                    {"Insurance, infrastructure and food or energy prices feel the strain; debate over who pays and who was prepared intensifies.",
                     "Funding and projects move from announcement to delivery, with early results and some friction over costs and siting."},
                    {"Pressure grows for adaptation spending and stricter policy, with repeated events reinforcing the political case.",
                     "Cumulative progress strengthens momentum for further commitments, though gaps between pledges and results remain."}};
            case HEALTH -> new String[][]{
                    {"Worries about {t}{c} grow: expect more testing, official advisories and strained services.",
                     "Encouraging news about {t}{c} spreads: expect wider trials, approvals or rollout announcements."},
                    {"Capacity pressures, funding disputes and public anxiety rise, while experts argue over the right response.",
                     "Rollout expands, access and cost become the main issues, and early real-world data is scrutinised."},
                    {"Systemic reforms or new preventive measures follow if the pressure persists.",
                     "Benefits become measurable over time, shifting the debate to long-term funding and equity of access."}};
            default -> new String[][]{
                    {"Attention on {t}{c} sharpens and the situation worsens before it improves, drawing in more voices and stakeholders.",
                     "Attention on {t}{c} sharpens and the situation steadily improves, drawing in supportive voices and stakeholders."},
                    {"Institutions, businesses and communities adjust, and secondary effects appear in related areas.",
                     "Institutions, businesses and communities build on the progress, and beneficial secondary effects appear."},
                    {"The story settles into a new normal that shapes later decisions and is referenced in future debates.",
                     "The story settles into a new normal and is cited as precedent in future decisions."}};
        };
    }

    static String fill(String template, String label, String ctx) {
        return template.replace("{t}", "\"" + label + "\"").replace("{c}", ctx);
    }

    // ------------------------------------------------------------------ formatting

    static String joinNatural(List<String> items) {
        if (items.size() == 1) return items.get(0);
        return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.get(items.size() - 1);
    }

    static String wrap(String text, String indent) { return wrap(text, indent, indent); }

    /** Word-wrap to WRAP columns, with one indent for the first line and another for continuation lines. */
    static String wrap(String text, String first, String rest) {
        StringBuilder out = new StringBuilder();
        String indent = first;
        StringBuilder line = new StringBuilder(indent);
        for (String word : text.split(" ")) {
            if (line.length() + word.length() + 1 > WRAP && line.length() > indent.length()) {
                out.append(line.toString().stripTrailing()).append('\n');
                indent = rest;
                line = new StringBuilder(indent);
            }
            if (line.length() > indent.length()) line.append(' ');
            line.append(word);
        }
        return out.append(line).toString();
    }

    // ------------------------------------------------------------------ demo data

    // Each row: source, title, body, hours before NOW.
    static final String[][] DEMO_PORT = {
            {"demo-wire-a", "Port strike halts shipping as talks collapse", "Dockworkers walked out; supply chain delays warned across retailers", "1"},
            {"demo-wire-a", "Retailers warn of shortages as port strike enters second day", "Shipping firms reroute cargo, prices expected to rise", "2"},
            {"demo-wire-b", "Port strike threatens holiday supply, business groups say", "Business leaders fear damage to growth as shipping stalls", "2"},
            {"demo-wire-b", "Union rejects offer as port strike deepens", "Talks collapse again; shipping delays spread", "3"},
            {"demo-wire-c", "Markets slide as port strike hits shipping stocks", "Shares fall, analysts warn of inflation risk from supply delays", "3"},
            {"demo-wire-c", "Government urged to intervene in port strike", "Ministers face pressure as shortages loom and prices rise", "4"},
            {"demo-wire-a", "Port strike: what it means for prices", "Economists warn of a hit to growth if shipping delays continue", "5"},
            {"demo-wire-b", "Port strike talks resume under mediator", "Union and employers meet as shipping backlog grows", "6"},
            {"demo-wire-c", "Supermarkets ration goods as port strike drags on", "Shortages spread to fresh produce as shipping delays persist", "7"},
            {"demo-wire-a", "Port strike costs economy millions a day", "Business groups warn of lasting damage to trade", "10"}};

    static final String[][] DEMO_CHIP = {
            {"demo-wire-b", "Chipmaker unveils new AI processor", "Launch boosts investor hope in the chip sector", "9"},
            {"demo-wire-c", "Rivals race to match AI chip launch", "Chip designers promise faster processors next year", "5"},
            {"demo-wire-a", "AI chip demand lifts tech stocks", "Investors cheer record orders for the new processor", "4"},
            {"demo-wire-b", "Regulators review AI chip export rules", "Officials weigh security concerns over processor sales", "11"},
            {"demo-wire-c", "Cloud firms sign deals for AI chip capacity", "Data centre operators agree multi-year processor orders", "2"}};

    static final String[][] DEMO_STORM = {
            {"demo-wire-a", "Storm warnings issued as heavy rain sweeps north", "Forecasters warn of flooding and high winds", "1"},
            {"demo-wire-b", "Flood alerts in place as storm brings travel chaos", "Roads closed and trains cancelled across the region", "2"},
            {"demo-wire-c", "Storm damage leaves thousands without power", "Engineers work overnight to restore electricity", "3"},
            {"demo-wire-a", "Rail services suspended as storm batters coast", "Operators urge passengers not to travel", "5"},
            {"demo-wire-b", "Councils open rest centres as storm floods homes", "Residents evacuated as river levels rise", "6"},
            {"demo-wire-c", "Insurers brace for storm claims", "Damage estimates climb as the clean-up begins", "8"}};

    static final String[][] DEMO_FILLER = {
            {"demo-wire-c", "Council approves new cycling scheme", "Residents welcome plan", "12"},
            {"demo-wire-a", "Local team wins cup final", "Fans celebrate record crowd", "14"},
            {"demo-wire-b", "Museum reopens after restoration", "Visitors praise restored gallery", "16"}};

    /**
     * Synthetic headlines that change from day to day, so --demo with --asof shows a storyline: a port
     * strike that slowly fades, a steady AI chip story, and a storm that breaks on day 2 and grows.
     * The mix is random but reproducible: the same date always gives the same headlines.
     */
    static List<Article> demoArticles() {
        int day = Math.floorMod(ChronoUnit.DAYS.between(DEMO_START, TODAY), 14);
        Random rnd = new Random(TODAY.toEpochDay());
        List<Article> out = new ArrayList<>();
        addDemo(out, DEMO_PORT, Math.max(0.35, 0.95 - 0.07 * day), rnd);
        addDemo(out, DEMO_CHIP, 0.75, rnd);
        addDemo(out, DEMO_STORM, day < 2 ? 0 : Math.min(0.95, 0.55 + 0.15 * (day - 2)), rnd);
        addDemo(out, DEMO_FILLER, 1.0, rnd);
        return out;
    }

    static void addDemo(List<Article> out, String[][] rows, double p, Random rnd) {
        for (String[] r : rows)
            if (rnd.nextDouble() < p)
                out.add(new Article(r[0], r[1], r[2], NOW.minus(Duration.ofHours(Long.parseLong(r[3]))), ""));
    }

    // ================================================================== Swing GUI (--gui)

    /**
     * The desktop window. It reads the same data directory as the terminal version and never writes to it,
     * so it is safe to keep open while a scheduled run saves new results; it reloads when that happens.
     */
    static final class Gui {
        static final Color INK = new Color(0x1f2937), MUTED = new Color(0x6b7280), GRID = new Color(0xe5e7eb),
                AXIS = new Color(0x9ca3af), CASSANDRA = new Color(0x2563eb), RAW = new Color(0x93b4f5),
                BASE = new Color(0x4b5563), COIN = new Color(0xc0c4cc), GOOD = new Color(0x1e7b3a),
                BAD = new Color(0xb42318);

        static final int MIN_TYPE_SKILL = 10; // comparable predictions before a type's skill is coloured

        final JFrame frame = new JFrame("Cassandra");
        final JLabel folderLabel = new JLabel();
        final JLabel status = new JLabel(" ");
        final JPanel todayHost = new JPanel(new BorderLayout());
        final JPanel accuracyHost = new JPanel(new BorderLayout());
        Path dir;
        long lastModified = Long.MIN_VALUE;
        String selectedTerm;   // the story picked on the Today tab, kept across reloads

        /** Everything the window shows, read from the data folder in one go. Any part may be missing. */
        record Loaded(Accuracy accuracy, Latest latest, List<HistRow> history, Map<String, Pred> preds) {}

        Gui(Path dir) {
            this.dir = dir;
            frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);

            JPanel bar = new JPanel(new BorderLayout(12, 0));
            bar.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
            JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            left.add(new JLabel("Data folder:"));
            folderLabel.setFont(folderLabel.getFont().deriveFont(Font.BOLD));
            left.add(folderLabel);
            JButton choose = new JButton("Choose…");
            choose.addActionListener(e -> chooseFolder());
            JButton reload = new JButton("Reload");
            reload.addActionListener(e -> reload());
            left.add(choose);
            left.add(reload);
            bar.add(left, BorderLayout.WEST);
            status.setForeground(MUTED);
            bar.add(status, BorderLayout.EAST);

            JTabbedPane tabs = new JTabbedPane();
            tabs.addTab("Today", todayHost);
            tabs.addTab("Accuracy", accuracyHost);
            frame.getContentPane().add(bar, BorderLayout.NORTH);
            frame.getContentPane().add(tabs, BorderLayout.CENTER);

            // About box: Help menu everywhere, plus the application menu's About item on a Mac
            JMenuBar menus = new JMenuBar();
            JMenu help = new JMenu("Help");
            JMenuItem about = new JMenuItem("About Cassandra");
            about.addActionListener(e -> showAbout());
            help.add(about);
            menus.add(help);
            frame.setJMenuBar(menus);
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.APP_ABOUT))
                java.awt.Desktop.getDesktop().setAboutHandler(e -> showAbout());
            frame.setSize(1120, 900);
            frame.setMinimumSize(new Dimension(980, 600));
            frame.setLocationRelativeTo(null);

            // pick up new grades when a scheduled run saves while the window is open
            new javax.swing.Timer(5000, e -> { if (modified() != lastModified) reload(); }).start();
            reload();
        }

        /** Newest change to the files the window shows; a change triggers a reload. */
        long modified() {
            long m = -1;
            for (String f : List.of("predictions.tsv", LATEST))
                try { m = Math.max(m, Files.getLastModifiedTime(dir.resolve(f)).toMillis()); }
                catch (IOException ignored) { /* file not there yet */ }
            return m;
        }

        void chooseFolder() {
            JFileChooser fc = new JFileChooser(dir.toAbsolutePath().toFile());
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fc.setDialogTitle("Choose a Cassandra data folder (the one holding predictions.tsv)");
            if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
                dir = fc.getSelectedFile().toPath();
                reload();
            }
        }

        void reload() {
            String full = dir.toAbsolutePath().normalize().toString();
            folderLabel.setText(full.length() <= 48 ? full : "…" + full.substring(full.length() - 47));
            folderLabel.setToolTipText(full);
            lastModified = modified();
            status.setText("Loading…");
            Path from = dir;
            new SwingWorker<Loaded, Void>() {
                @Override protected Loaded doInBackground() {
                    boolean any = Files.exists(from.resolve("predictions.tsv"));
                    List<Pred> preds = any ? loadPreds(from) : List.of();
                    Map<String, Pred> byId = new HashMap<>();
                    for (Pred p : preds) byId.put(p.id(), p);
                    return new Loaded(any ? computeAccuracy(preds, from) : null, loadLatest(from),
                            loadHistory(from), byId);
                }
                @Override protected void done() {
                    Loaded l;
                    try { l = get(); } catch (Exception ex) { l = new Loaded(null, null, List.of(), Map.of()); }
                    show(todayHost, todayView(l));
                    show(accuracyHost, accuracyView(l.accuracy()));
                    Accuracy a = l.accuracy();
                    status.setText(a == null ? "No predictions in this folder" : String.format(Locale.ROOT,
                            "%d predictions: %d graded, %d pending, %d void", a.logged(), a.graded().size(),
                            a.pending(), a.voided()));
                }
            }.execute();
        }

        static void show(JPanel host, JComponent view) {
            host.removeAll();
            host.add(view, BorderLayout.CENTER);
            host.revalidate();
            host.repaint();
        }

        // ---------------------------------------------------------------- Today tab

        static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK);
        static final DateTimeFormatter LONG_DAY = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.UK);
        static final Color SELECTED = new Color(0x2563eb), BAND = new Color(37, 99, 235, 40);

        JComponent todayView(Loaded data) {
            Latest l = data.latest();
            if (l == null)
                return message(data.accuracy() == null ? "No runs in this folder yet." : "No snapshot of the latest run yet.",
                        data.accuracy() == null
                                ? "Run Cassandra, or choose the folder that holds her data (cassandra-data by default, "
                                + "cassandra-demo-data for --demo)."
                                : "Each run now saves what it found to latest-run.tsv for this tab. It will appear "
                                + "after Cassandra's next run.");

            List<SnapStory> stories = l.stories();
            SnapStory chosen = stories.stream().filter(s -> s.term().equals(selectedTerm)).findFirst()
                    .orElse(stories.get(0));
            selectedTerm = chosen.term();

            JPanel page = new Page();
            page.setLayout(new BorderLayout(0, 12));
            page.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
            page.add(runHeader(l), BorderLayout.NORTH);

            // left: one card per story; right: the selected story's coverage chart and headlines
            JPanel cards = new JPanel();
            cards.setLayout(new BoxLayout(cards, BoxLayout.Y_AXIS));
            JPanel chartHost = new JPanel(new BorderLayout());
            JPanel headlineHost = new JPanel(new BorderLayout());
            List<StoryCard> all = new ArrayList<>();
            for (SnapStory s : stories) {
                List<Pred> ps = l.predictionIds().stream().map(data.preds()::get).filter(Objects::nonNull)
                        .filter(p -> p.term().equals(s.term())).toList();
                StoryCard card = new StoryCard(s, ps);
                all.add(card);
                cards.add(card);
                cards.add(Box.createVerticalStrut(10));
            }
            Runnable[] select = new Runnable[1];
            for (StoryCard card : all)
                card.addMouseListener(new java.awt.event.MouseAdapter() {
                    @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                        selectedTerm = card.story.term();
                        select[0].run();
                    }
                });
            select[0] = () -> {
                SnapStory s = all.stream().map(c -> c.story).filter(x -> x.term().equals(selectedTerm))
                        .findFirst().orElse(stories.get(0));
                for (StoryCard c : all) c.setSelected(c.story == s);
                show(chartHost, card(new CoverageChart(s, coverage(data.history(), s, l.date()), l.date())));
                show(headlineHost, headlines(s));
            };
            select[0].run();

            JPanel right = new JPanel(new BorderLayout(0, 12));
            right.add(chartHost, BorderLayout.CENTER);
            right.add(headlineHost, BorderLayout.SOUTH);

            JPanel cols = new JPanel(new java.awt.GridBagLayout());
            java.awt.GridBagConstraints g = new java.awt.GridBagConstraints();
            g.fill = java.awt.GridBagConstraints.BOTH;
            g.gridy = 0;
            g.weighty = 1;
            g.gridx = 0;
            g.weightx = 0.46;
            JPanel cardCol = new JPanel(new BorderLayout());
            cardCol.add(cards, BorderLayout.NORTH);
            cols.add(cardCol, g);
            g.gridx = 1;
            g.weightx = 0.54;
            g.insets = new Insets(0, 12, 0, 0);
            cols.add(right, g);
            page.add(cols, BorderLayout.CENTER);
            page.add(scenarioPanel(l), BorderLayout.SOUTH);

            return scrollPage(page);
        }

        JComponent runHeader(Latest l) {
            JPanel p = new JPanel(new BorderLayout(0, 4));
            JLabel h = new JLabel("Latest run: " + l.date().format(LONG_DAY));
            h.setFont(h.getFont().deriveFont(Font.BOLD, 20f));
            h.setForeground(INK);
            p.add(h, BorderLayout.NORTH);
            String sub = String.format(Locale.ROOT, "%d headlines. Top story's domain: %s. Click a story to see its "
                    + "coverage chart and headlines.", l.headlines(), l.domain().name().toLowerCase());
            p.add(wrapped(sub, MUTED), BorderLayout.CENTER);

            long age = ChronoUnit.DAYS.between(l.date(), LocalDate.now(ZoneOffset.UTC));
            if (age >= 2) {
                JPanel warn = new JPanel(new BorderLayout());
                warn.setBackground(new Color(0xf9eeda));
                warn.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
                warn.add(wrapped("This run is " + age + " days old. Cassandra may not have run since: check your "
                        + "scheduled job. Predictions not graded within " + GRACE_DAYS + " days of falling due are "
                        + "voided.", new Color(0x8a5a00)), BorderLayout.CENTER);
                p.add(warn, BorderLayout.SOUTH);
            }
            return p;
        }

        static String shortStatement(Pred p) {
            String due = p.due().format(DAY);
            return switch (p.kind()) {
                case "TOP5" -> "Still in the top 5 on " + due;
                case "SHARE_UP" -> "A bigger share than today's "
                        + String.format(Locale.ROOT, "%.0f%%", Double.parseDouble(p.param()) * 100) + " on " + due;
                case "TONE_PERSIST" -> "Coverage still " + (p.param().equals("NEG") ? "negative" : "positive")
                        + " on " + due;
                default -> p.kind() + " on " + due;
            };
        }

        /** One story: its headline numbers and the predictions made about it. Click to select. */
        @SuppressWarnings("serial")
        static final class StoryCard extends JPanel {
            final SnapStory story;

            StoryCard(SnapStory s, List<Pred> preds) {
                this.story = s;
                setLayout(new BorderLayout(0, 8));
                setBackground(Color.WHITE);
                setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
                setSelected(false);

                JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
                top.setOpaque(false);
                JLabel rank = new JLabel(String.valueOf(s.rank()));
                rank.setFont(rank.getFont().deriveFont(Font.BOLD, 13f));
                rank.setForeground(Color.WHITE);
                rank.setOpaque(true);
                rank.setBackground(INK);
                rank.setBorder(BorderFactory.createEmptyBorder(2, 7, 2, 7));
                top.add(rank);
                JLabel name = new JLabel(s.label());
                name.setFont(name.getFont().deriveFont(Font.BOLD, 17f));
                name.setForeground(INK);
                top.add(name);
                if (s.isNew()) {
                    JLabel chip = new JLabel("new today");
                    chip.setFont(chip.getFont().deriveFont(Font.BOLD, 11f));
                    chip.setForeground(new Color(0x5b3cc4));
                    chip.setOpaque(true);
                    chip.setBackground(new Color(0xefedff));
                    chip.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
                    top.add(chip);
                }

                String facts = String.format(Locale.ROOT, "%.0f%% of headlines · %d articles · tone %s "
                        + "(%+.1f) · momentum %s", s.share() * 100, s.articles(), s.tone().name().toLowerCase(),
                        s.net(), s.momentum());
                JPanel head = new JPanel(new BorderLayout(0, 4));
                head.setOpaque(false);
                head.add(top, BorderLayout.NORTH);
                JLabel f = new JLabel(facts);
                f.setForeground(MUTED);
                f.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
                head.add(f, BorderLayout.CENTER);
                add(head, BorderLayout.NORTH);

                JPanel rows = new JPanel(new java.awt.GridBagLayout());
                rows.setOpaque(false);
                java.awt.GridBagConstraints g = new java.awt.GridBagConstraints();
                g.insets = new Insets(3, 8, 3, 4);
                g.anchor = java.awt.GridBagConstraints.WEST;
                int y = 0;
                for (Pred p : preds) {
                    g.gridy = y++;
                    g.gridx = 0;
                    g.weightx = 1;
                    g.fill = java.awt.GridBagConstraints.HORIZONTAL;
                    JLabel t = new JLabel(shortStatement(p));
                    t.setForeground(INK);
                    t.setToolTipText(statement(p));
                    rows.add(t, g);
                    g.gridx = 1;
                    g.weightx = 0;
                    g.fill = java.awt.GridBagConstraints.NONE;
                    rows.add(new ProbBar(p), g);
                    g.gridx = 2;
                    JLabel st = new JLabel(switch (p.outcome()) {
                        case "TRUE" -> "came true";
                        case "FALSE" -> "didn't happen";
                        case "VOID" -> "void";
                        default -> "pending";
                    });
                    st.setForeground(p.outcome().equals("TRUE") ? GOOD : p.outcome().equals("FALSE") ? BAD : MUTED);
                    st.setFont(st.getFont().deriveFont(11.5f));
                    st.setPreferredSize(new Dimension(80, st.getPreferredSize().height));
                    rows.add(st, g);
                }
                if (preds.isEmpty()) {
                    JLabel none = new JLabel("No predictions saved for this story.");
                    none.setForeground(MUTED);
                    rows.add(none, g);
                }
                add(rows, BorderLayout.CENTER);
            }

            void setSelected(boolean on) {
                setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(on ? SELECTED : GRID, on ? 2 : 1),
                        BorderFactory.createEmptyBorder(on ? 9 : 10, on ? 7 : 8, on ? 9 : 10, on ? 7 : 8)));
            }

            @Override public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        }

        /** A probability as a short horizontal bar with its percentage. */
        @SuppressWarnings("serial")
        static final class ProbBar extends JComponent {
            final Pred p;

            ProbBar(Pred p) {
                this.p = p;
                setPreferredSize(new Dimension(122, 18));
                setToolTipText(Math.abs(p.p() - p.pRaw()) > 1e-9
                        ? "Recalibrated from " + pct(p.pRaw()) + " using her track record for this type"
                        : "Her probability that this comes true");
            }

            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = smooth(g0);
                int w = 80, h = 8, y = (getHeight() - h) / 2;
                g.setColor(GRID);
                g.fillRoundRect(0, y, w, h, h, h);
                g.setColor(CASSANDRA);
                g.fillRoundRect(0, y, (int) Math.round(w * p.p()), h, h, h);
                g.setColor(INK);
                g.setFont(UIManager.getFont("Label.font").deriveFont(Font.BOLD, 12f));
                g.drawString(pct(p.p()), w + 8, y + h);
                g.dispose();
            }
        }

        /**
         * The story's daily share of headlines from the day it first appeared up to the run date, as
         * (days relative to the run date, share) pairs. A day it was out of the top 10 counts as 0, as in the trend fit.
         */
        static List<double[]> coverage(List<HistRow> history, SnapStory s, LocalDate runDate) {
            LocalDate first = s.firstSeen() == null ? runDate : s.firstSeen();
            List<double[]> pts = new ArrayList<>();
            List<LocalDate> dates = history.stream().map(HistRow::date)
                    .filter(d -> !d.isBefore(first) && !d.isAfter(runDate)).distinct().sorted().toList();
            for (LocalDate d : dates) {
                double share = history.stream().filter(r -> r.date().equals(d) && r.term().equals(s.term()))
                        .findFirst().map(HistRow::share).orElse(0.0);
                pts.add(new double[]{ChronoUnit.DAYS.between(runDate, d), share});
            }
            if (pts.isEmpty() || pts.get(pts.size() - 1)[0] != 0) pts.add(new double[]{0, s.share()});
            return pts;
        }

        JComponent headlines(SnapStory s) {
            JPanel p = new JPanel();
            p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
            p.setBackground(Color.WHITE);
            p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(GRID),
                    BorderFactory.createEmptyBorder(12, 16, 12, 16)));
            JLabel h = new JLabel("Latest headlines about “" + s.label() + "”");
            h.setFont(h.getFont().deriveFont(Font.BOLD, 14f));
            h.setForeground(INK);
            h.setAlignmentX(Component.LEFT_ALIGNMENT);
            p.add(h);
            p.add(Box.createVerticalStrut(8));
            DateTimeFormatter when = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.UK)
                    .withZone(java.time.ZoneId.systemDefault());
            boolean anyLink = false;
            for (SnapArticle a : s.headlines()) {
                boolean web = a.link().startsWith("https://") || a.link().startsWith("http://");
                anyLink |= web;
                JLabel t = new JLabel(web ? "<html><a href=''>" + escape(a.title()) + "</a></html>" : a.title());
                t.setForeground(web ? CASSANDRA : INK);
                t.setAlignmentX(Component.LEFT_ALIGNMENT);
                if (web) {
                    t.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
                    t.setToolTipText(a.link());
                    t.addMouseListener(new java.awt.event.MouseAdapter() {
                        @Override public void mouseClicked(java.awt.event.MouseEvent e) { browse(a.link()); }
                    });
                }
                p.add(t);
                JLabel meta = new JLabel(a.source() + " · " + when.format(a.published()));
                meta.setForeground(MUTED);
                meta.setFont(meta.getFont().deriveFont(11f));
                meta.setAlignmentX(Component.LEFT_ALIGNMENT);
                p.add(meta);
                p.add(Box.createVerticalStrut(6));
            }
            if (!anyLink && !s.headlines().isEmpty()) {
                JLabel note = new JLabel("These headlines have no links (demo data, or a feed that gives none).");
                note.setForeground(MUTED);
                note.setFont(note.getFont().deriveFont(Font.ITALIC, 11f));
                note.setAlignmentX(Component.LEFT_ALIGNMENT);
                p.add(note);
            }
            return p;
        }

        static String escape(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }

        void browse(String link) {
            try {
                if (java.awt.Desktop.isDesktopSupported()
                        && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE))
                    java.awt.Desktop.getDesktop().browse(URI.create(link));
                else
                    JOptionPane.showMessageDialog(frame, "Can't open a browser here. The link is:\n" + link);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(frame, "Couldn't open " + link + "\n" + e.getMessage());
            }
        }

        JComponent scenarioPanel(Latest l) {
            JPanel p = new JPanel(new BorderLayout(0, 8));
            p.setBackground(Color.WHITE);
            p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(GRID),
                    BorderFactory.createEmptyBorder(12, 16, 12, 16)));
            JLabel h = new JLabel("What may follow for “" + l.stories().get(0).label() + "”");
            h.setFont(h.getFont().deriveFont(Font.BOLD, 14f));
            h.setForeground(INK);
            JPanel head = new JPanel(new BorderLayout(0, 2));
            head.setOpaque(false);
            head.add(h, BorderLayout.NORTH);
            head.add(wrapped("A template scenario for the top story, not a prediction: it is not graded and will sound "
                    + "plausible whatever happens. Scenario confidence: " + l.scenario().confidence().toLowerCase()
                    + ".", MUTED), BorderLayout.CENTER);
            p.add(head, BorderLayout.NORTH);

            JPanel body = new JPanel(new java.awt.GridBagLayout());
            body.setOpaque(false);
            java.awt.GridBagConstraints g = new java.awt.GridBagConstraints();
            g.anchor = java.awt.GridBagConstraints.NORTHWEST;
            g.insets = new Insets(3, 0, 3, 12);
            String[] horizons = {"Next few days", "Coming weeks", "Coming months", "If not"};
            List<String> texts = new ArrayList<>(l.scenario().steps());
            texts.add(l.scenario().alternative());
            for (int i = 0; i < texts.size() && i < horizons.length; i++) {
                g.gridy = i;
                g.gridx = 0;
                g.weightx = 0;
                g.fill = java.awt.GridBagConstraints.NONE;
                JLabel k = new JLabel(horizons[i]);
                k.setFont(k.getFont().deriveFont(Font.BOLD));
                k.setForeground(i == 3 ? MUTED : INK);
                k.setPreferredSize(new Dimension(110, k.getPreferredSize().height));
                body.add(k, g);
                g.gridx = 1;
                g.weightx = 1;
                g.fill = java.awt.GridBagConstraints.HORIZONTAL;
                body.add(wrapped(texts.get(i), i == 3 ? MUTED : INK), g);
            }
            p.add(body, BorderLayout.CENTER);
            if (!l.others().isEmpty()) {
                List<String> o = l.others().stream().map(x -> x.label() + " (" + x.articles() + ")").toList();
                p.add(wrapped("Other storylines to watch: " + String.join(", ", o) + ".", MUTED), BorderLayout.SOUTH);
            }
            return p;
        }

        /** A story's share of headlines since it appeared, with the fitted trend and its 80% range 7 days ahead. */
        @SuppressWarnings("serial")
        static final class CoverageChart extends JComponent {
            final SnapStory s;
            final List<double[]> pts;
            final LocalDate runDate;

            CoverageChart(SnapStory s, List<double[]> pts, LocalDate runDate) {
                this.s = s;
                this.pts = pts;
                this.runDate = runDate;
                setPreferredSize(new Dimension(520, 330));
            }

            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = smooth(g0);
                int w = getWidth(), h = getHeight();
                Forecast fc = s.fc();
                String sub;
                if (fc != null)
                    sub = String.format(Locale.ROOT, "Trend since %s: %+.1f points a day. Shaded: 80%% range ahead.",
                            s.firstSeen().format(DAY), fc.slope() * 100);
                else if (s.isNew())
                    sub = "New today: no trend until it has 3 days of data.";
                else if (s.firstSeen() != null)
                    sub = "First seen " + s.firstSeen().format(DAY) + ": a trend needs 3 days of data.";
                else
                    sub = "Not enough run history yet to fit a trend.";
                title(g, "Share of headlines: “" + s.label() + "”", sub, 16, 24);
                Font small = UIManager.getFont("Label.font").deriveFont(11f);
                g.setFont(small);

                int left = 50, right = 46, top = 72, bottom = 40;
                int x0 = left, x1 = w - right, y0 = top, y1 = h - bottom;
                double minX = Math.min(-1, pts.get(0)[0]), maxX = fc != null ? 7 : 0.5;
                double hi = pts.stream().mapToDouble(p -> p[1]).max().orElse(0.1);
                if (fc != null)
                    for (int d = 0; d <= 7; d++) hi = Math.max(hi, clamp(fc.at(d) + 1.2816 * fc.predSd(d), 0, 1));
                double ymax = Math.min(1, Math.max(0.1, Math.ceil(hi * 1.1 / 0.1) * 0.1));
                java.util.function.DoubleUnaryOperator X = x -> x0 + (x1 - x0) * (x - minX) / (maxX - minX);
                java.util.function.DoubleUnaryOperator Y = v -> y1 - (y1 - y0) * v / ymax;

                double step = ymax > 0.5 ? 0.2 : 0.1;
                for (double t = 0; t <= ymax + 1e-9; t += step) {
                    int y = (int) Y.applyAsDouble(t);
                    g.setColor(GRID);
                    g.drawLine(x0, y, x1, y);
                    g.setColor(MUTED);
                    textRight(g, String.format(Locale.ROOT, "%.0f%%", t * 100), x0 - 6, y + 4);
                }
                int span = (int) (maxX - minX);
                int every = Math.max(1, (int) Math.ceil(span / 7.0));
                for (int d = (int) Math.ceil(minX); d <= (int) maxX; d++) {
                    if (d != 0 && Math.floorMod(d, every) != 0) continue;
                    int x = (int) X.applyAsDouble(d);
                    g.setColor(d == 0 ? INK : MUTED);
                    textCentre(g, runDate.plusDays(d).format(DateTimeFormatter.ofPattern("d MMM", Locale.UK)), x, y1 + 16);
                }

                // run date
                int xr = (int) X.applyAsDouble(0);
                g.setColor(AXIS);
                g.setStroke(DASHED);
                g.drawLine(xr, y0, xr, y1);
                g.setStroke(new BasicStroke(1f));
                g.setColor(MUTED);
                textCentre(g, "run date", xr, y0 - 4);

                if (fc != null) {
                    // 80% range ahead, then the fitted line (solid over the data, dashed ahead)
                    java.awt.geom.Path2D band = new java.awt.geom.Path2D.Double();
                    for (int i = 0; i <= 28; i++) {
                        double d = i / 4.0, v = clamp(fc.at(d) + 1.2816 * fc.predSd(d), 0, 1);
                        if (i == 0) band.moveTo(X.applyAsDouble(d), Y.applyAsDouble(v));
                        else band.lineTo(X.applyAsDouble(d), Y.applyAsDouble(v));
                    }
                    for (int i = 28; i >= 0; i--) {
                        double d = i / 4.0, v = clamp(fc.at(d) - 1.2816 * fc.predSd(d), 0, 1);
                        band.lineTo(X.applyAsDouble(d), Y.applyAsDouble(v));
                    }
                    band.closePath();
                    g.setColor(BAND);
                    g.fill(band);
                    g.setColor(AXIS);
                    g.setStroke(new BasicStroke(1.5f));
                    g.drawLine((int) X.applyAsDouble(pts.get(0)[0]), (int) Y.applyAsDouble(clamp(fc.at(pts.get(0)[0]), 0, 1)),
                            xr, (int) Y.applyAsDouble(clamp(fc.at(0), 0, 1)));
                    g.setColor(CASSANDRA);
                    g.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f,
                            new float[]{6f, 4f}, 0f));
                    g.drawLine(xr, (int) Y.applyAsDouble(clamp(fc.at(0), 0, 1)),
                            (int) X.applyAsDouble(7), (int) Y.applyAsDouble(clamp(fc.at(7), 0, 1)));
                    g.setStroke(new BasicStroke(1.5f));
                    for (int d : new int[]{3, 7}) {
                        double v = clamp(fc.at(d), 0, 1);
                        int x = (int) X.applyAsDouble(d), y = (int) Y.applyAsDouble(v);
                        g.setColor(Color.WHITE);
                        g.fillOval(x - 4, y - 4, 8, 8);
                        g.setColor(CASSANDRA);
                        g.drawOval(x - 4, y - 4, 8, 8);
                        g.setColor(INK);
                        textCentre(g, String.format(Locale.ROOT, "≈%.0f%%", v * 100), x, y - 9);
                    }
                }

                // observed shares
                java.awt.geom.Path2D line = new java.awt.geom.Path2D.Double();
                for (int i = 0; i < pts.size(); i++) {
                    double x = X.applyAsDouble(pts.get(i)[0]), y = Y.applyAsDouble(pts.get(i)[1]);
                    if (i == 0) line.moveTo(x, y); else line.lineTo(x, y);
                }
                g.setColor(INK);
                g.setStroke(new BasicStroke(2f));
                g.draw(line);
                for (double[] p : pts) {
                    double x = X.applyAsDouble(p[0]), y = Y.applyAsDouble(p[1]);
                    g.fill(new java.awt.geom.Ellipse2D.Double(x - 3.5, y - 3.5, 7, 7));
                }
                double[] last = pts.get(pts.size() - 1);
                g.setFont(small.deriveFont(Font.BOLD));
                g.drawString(String.format(Locale.ROOT, "%.0f%%", last[1] * 100),
                        (int) X.applyAsDouble(last[0]) - 30, (int) Y.applyAsDouble(last[1]) - 8);
                g.dispose();
            }
        }

        // ---------------------------------------------------------------- Accuracy tab

        JComponent accuracyView(Accuracy a) {
            if (a == null)
                return message("No predictions in this folder yet.",
                        "Run Cassandra a few times, or choose the folder that holds her predictions.tsv "
                                + "(cassandra-data by default, cassandra-demo-data for --demo).");
            if (a.graded().isEmpty())
                return message("Nothing has been graded yet.", a.logged() + " predictions are logged. They fall due 3 "
                        + "and 7 days after they are made, so the first scores appear after a few daily runs.");

            JPanel page = new Page();
            page.setLayout(new BorderLayout(0, 12));
            page.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

            JPanel head = new JPanel(new BorderLayout(0, 10));
            head.add(banner(a), BorderLayout.NORTH);
            head.add(tiles(a), BorderLayout.CENTER);
            page.add(head, BorderLayout.NORTH);

            JPanel charts = new JPanel(new GridLayout(1, 2, 12, 0));
            charts.add(card(new ReliabilityChart(a)));
            charts.add(card(new BrierChart(a)));
            page.add(charts, BorderLayout.CENTER);
            page.add(typeTable(a), BorderLayout.SOUTH);

            return scrollPage(page);
        }

        /**
         * A page that always fits the window's width, stretches to fill a tall window, and scrolls
         * vertically rather than squashing the charts when the window is short.
         */
        @SuppressWarnings("serial")
        static final class Page extends JPanel implements Scrollable {
            @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
            @Override public int getScrollableUnitIncrement(java.awt.Rectangle r, int o, int d) { return 16; }
            @Override public int getScrollableBlockIncrement(java.awt.Rectangle r, int o, int d) { return r.height; }
            @Override public boolean getScrollableTracksViewportWidth() { return true; }
            @Override public boolean getScrollableTracksViewportHeight() {
                return getParent() instanceof JViewport v && v.getHeight() > getPreferredSize().height;
            }
        }

        JComponent banner(Accuracy a) {
            int nComp = a.comparable().size();
            String head, sub;
            Color bg, fg;
            switch (a.verdict()) {
                case BEATING -> {
                    head = "Beating the base rate";
                    sub = "Her probabilities carry information beyond \"what usually happens\", by more than luck "
                            + "alone would easily explain.";
                    bg = new Color(0xe5f4e4); fg = GOOD;
                }
                case WORSE -> {
                    head = "Worse than the base rate";
                    sub = "Simply forecasting how often each type of claim usually comes true would do better. "
                            + "Treat her probabilities with suspicion.";
                    bg = new Color(0xfbebeb); fg = BAD;
                }
                case UNCLEAR -> {
                    head = "No clear edge over the base rate";
                    sub = "Any difference between her and the base-rate forecaster is within what luck alone "
                            + "could produce.";
                    bg = new Color(0xf9eeda); fg = new Color(0x8a5a00);
                }
                default -> {
                    head = "Too early to judge";
                    sub = String.format(Locale.ROOT, "Only %d prediction(s) can be compared with the base-rate "
                            + "forecaster so far; a verdict needs %d.", nComp, MIN_FOR_VERDICT);
                    bg = new Color(0xf0efec); fg = INK;
                }
            }
            if (a.ci() != null)
                sub += String.format(Locale.ROOT, " Skill score %s over %d comparable predictions (90%% interval "
                        + "%+.2f to %+.2f).", num(a.skill(), "%+.2f"), nComp, a.ci()[0], a.ci()[1]);
            else if (nComp > 0)
                sub += " Provisional skill score: " + num(a.skill(), "%+.2f") + ".";

            JPanel p = new JPanel(new BorderLayout(0, 4));
            p.setBackground(bg);
            p.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
            JLabel h = new JLabel(head);
            h.setFont(h.getFont().deriveFont(Font.BOLD, 20f));
            h.setForeground(fg);
            p.add(h, BorderLayout.NORTH);
            p.add(wrapped(sub, INK), BorderLayout.CENTER);
            return p;
        }

        JComponent tiles(Accuracy a) {
            JPanel p = new JPanel(new GridLayout(1, 4, 12, 0));
            p.add(tile("Skill vs base rate", num(a.skill(), "%+.2f"),
                    a.ci() == null ? "provisional" : String.format(Locale.ROOT, "90%% interval %+.2f to %+.2f",
                            a.ci()[0], a.ci()[1]),
                    // a provisional score is shown in grey: colouring it would claim more than the data supports
                    a.ci() == null || Double.isNaN(a.skill()) ? MUTED : a.skill() > 0 ? GOOD : BAD));
            p.add(tile("Brier score", String.format(Locale.ROOT, "%.3f", a.bCass()),
                    "all " + a.graded().size() + " graded · 0 is perfect", INK));
            p.add(tile("Coin flip would score", String.format(Locale.ROOT, "%.3f", a.bCoin()),
                    "always 50% · the bare minimum to beat", MUTED));
            double d = a.bCass() - a.bRaw();
            p.add(tile("Recalibration", a.recalibrated() == 0 ? "not yet" : String.format(Locale.ROOT, "%+.3f", d),
                    a.recalibrated() == 0 ? "no graded prediction recalibrated"
                            : String.format(Locale.ROOT, "Brier %.3f → %.3f (%s)", a.bRaw(), a.bCass(),
                            d < 0 ? "better" : d > 0 ? "worse" : "same"),
                    a.recalibrated() == 0 ? MUTED : d < 0 ? GOOD : d > 0 ? BAD : INK));
            return p;
        }

        JComponent tile(String title, String value, String caption, Color valueColor) {
            JPanel p = new JPanel(new BorderLayout(0, 2));
            p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(GRID),
                    BorderFactory.createEmptyBorder(10, 12, 10, 12)));
            p.setBackground(Color.WHITE);
            JLabel t = new JLabel(title);
            t.setForeground(MUTED);
            JLabel v = new JLabel(value);
            v.setFont(v.getFont().deriveFont(Font.BOLD, 24f));
            v.setForeground(valueColor);
            JLabel c = new JLabel(caption);
            c.setForeground(MUTED);
            c.setFont(c.getFont().deriveFont(11f));
            p.add(t, BorderLayout.NORTH);
            p.add(v, BorderLayout.CENTER);
            p.add(c, BorderLayout.SOUTH);
            return p;
        }

        JComponent typeTable(Accuracy a) {
            String[] cols = {"Prediction type", "Graded", "Came true", "Avg forecast", "Brier", "Skill vs base rate",
                    "Compared on"};
            Object[][] rows = new Object[a.types().size()][];
            for (int i = 0; i < rows.length; i++) {
                TypeRow t = a.types().get(i);
                rows[i] = new Object[]{describeType(t.key()), t.n(), pct(t.hitRate()), pct(t.avgForecast()),
                        String.format(Locale.ROOT, "%.3f", t.brier()), num(t.skill(), "%+.2f"), t.comparable()};
            }
            JTable table = new JTable(new javax.swing.table.DefaultTableModel(rows, cols) {
                @Override public boolean isCellEditable(int r, int c) { return false; }
            });
            table.setRowHeight(24);
            table.setShowVerticalLines(false);
            table.setGridColor(GRID);
            table.getColumnModel().getColumn(0).setPreferredWidth(330);
            javax.swing.table.DefaultTableCellRenderer right = new javax.swing.table.DefaultTableCellRenderer() {
                @Override public Component getTableCellRendererComponent(JTable tb, Object v, boolean sel,
                                                                         boolean focus, int r, int c) {
                    Component comp = super.getTableCellRendererComponent(tb, v, sel, focus, r, c);
                    setHorizontalAlignment(SwingConstants.RIGHT);
                    if (!sel) {
                        TypeRow t = a.types().get(tb.convertRowIndexToModel(r));
                        double s = t.skill();
                        // skill on a handful of predictions is noise: grey until there are MIN_TYPE_SKILL of them
                        comp.setForeground(c != 5 || Double.isNaN(s) ? INK : t.comparable() < MIN_TYPE_SKILL ? MUTED
                                : s > 0.02 ? GOOD : s < -0.02 ? BAD : INK);
                    }
                    return comp;
                }
            };
            for (int c = 1; c < cols.length; c++) table.getColumnModel().getColumn(c).setCellRenderer(right);

            JScrollPane sp = new JScrollPane(table);
            sp.setPreferredSize(new Dimension(100, table.getRowHeight() * (rows.length + 1) + 6));
            JPanel p = new JPanel(new BorderLayout(0, 6));
            p.add(sp, BorderLayout.CENTER);
            p.add(wrapped("Skill: above 0 means better than the base-rate forecaster, which knows how often each type "
                    + "of claim has come true so far. A type that is almost always true is easy to score well on; "
                    + "skill shows whether Cassandra does better than simply knowing that. Skill is grey until a type "
                    + "has " + MIN_TYPE_SKILL + " comparable predictions. Hover the charts for details.",
                    MUTED), BorderLayout.SOUTH);
            return p;
        }

        static String describeType(String key) {
            String[] k = key.split("@");
            String what = switch (k[0]) {
                case "TOP5" -> "Still in the top 5";
                case "SHARE_UP" -> "Larger share of headlines";
                case "TONE_PERSIST" -> "Tone stays the same";
                default -> k[0];
            };
            return what + " after " + (k.length > 1 ? k[1] : "?") + " days  (" + key + ")";
        }

        // ---------------------------------------------------------------- small helpers

        static JComponent message(String head, String body) {
            JPanel p = new JPanel(new BorderLayout(0, 8));
            p.setBorder(BorderFactory.createEmptyBorder(40, 40, 40, 40));
            JLabel h = new JLabel(head);
            h.setFont(h.getFont().deriveFont(Font.BOLD, 18f));
            p.add(h, BorderLayout.NORTH);
            p.add(wrapped(body, MUTED), BorderLayout.CENTER);
            JPanel outer = new JPanel(new BorderLayout());
            outer.add(p, BorderLayout.NORTH);
            return outer;
        }

        static JTextArea wrapped(String text, Color color) {
            JTextArea t = new JTextArea(text);
            t.setLineWrap(true);
            t.setWrapStyleWord(true);
            t.setEditable(false);
            t.setFocusable(false);
            t.setOpaque(false);
            t.setForeground(color);
            t.setFont(UIManager.getFont("Label.font"));
            // a text area normally scrolls itself into view when created; this one is just a label
            ((javax.swing.text.DefaultCaret) t.getCaret()).setUpdatePolicy(javax.swing.text.DefaultCaret.NEVER_UPDATE);
            return t;
        }

        /** Wrap a page in a vertical scroll pane that opens at the top. */
        static JComponent scrollPage(JComponent page) {
            JScrollPane scroll = new JScrollPane(page, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                    ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.getVerticalScrollBar().setUnitIncrement(16);
            SwingUtilities.invokeLater(() -> scroll.getViewport().setViewPosition(new java.awt.Point(0, 0)));
            return scroll;
        }

        static JComponent card(JComponent c) {
            JPanel p = new JPanel(new BorderLayout());
            p.setBackground(Color.WHITE);
            p.setBorder(BorderFactory.createLineBorder(GRID));
            p.add(c, BorderLayout.CENTER);
            return p;
        }

        static Graphics2D smooth(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            return g2;
        }

        static final BasicStroke DASHED = new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND,
                10f, new float[]{5f, 4f}, 0f);

        static void title(Graphics2D g, String title, String subtitle, int x, int y) {
            Font base = UIManager.getFont("Label.font");
            g.setColor(INK);
            g.setFont(base.deriveFont(Font.BOLD, 14f));
            g.drawString(title, x, y);
            g.setColor(MUTED);
            g.setFont(base.deriveFont(11.5f));
            g.drawString(subtitle, x, y + 17);
        }

        static void textRight(Graphics2D g, String s, int x, int y) {
            g.drawString(s, x - g.getFontMetrics().stringWidth(s), y);
        }

        static void textCentre(Graphics2D g, String s, int x, int y) {
            g.drawString(s, x - g.getFontMetrics().stringWidth(s) / 2, y);
        }

        /** Wilson score interval for a proportion: a fair range for the true rate behind obs out of n. */
        static double[] wilson(double obs, int n, double z) {
            double z2 = z * z, d = 1 + z2 / n;
            double centre = (obs + z2 / (2 * n)) / d;
            double half = z * Math.sqrt(obs * (1 - obs) / n + z2 / (4.0 * n * n)) / d;
            return new double[]{Math.max(0, centre - half), Math.min(1, centre + half)};
        }

        // ---------------------------------------------------------------- reliability diagram

        /** Forecast probability against how often it came true, one dot per band; the diagonal is perfect. */
        @SuppressWarnings("serial")
        static final class ReliabilityChart extends JComponent {
            final Accuracy a;
            final List<double[]> hits = new ArrayList<>(); // x, y, radius, bin index (for tooltips)

            ReliabilityChart(Accuracy a) {
                this.a = a;
                setPreferredSize(new Dimension(480, 420));
                setToolTipText("");
            }

            @Override public String getToolTipText(java.awt.event.MouseEvent e) {
                for (double[] h : hits)
                    if (Math.hypot(e.getX() - h[0], e.getY() - h[1]) <= h[2] + 3) {
                        Bin b = a.bins().get((int) h[3]);
                        return String.format(Locale.ROOT, "<html><b>Forecasts of %.0f-%.0f%%</b><br>%d predictions, "
                                + "average forecast %.0f%%<br>came true %.0f%% of the time<br>%s</html>",
                                b.lo() * 100, b.hi() * 100, b.n(), b.avgForecast() * 100, b.observed() * 100, b.verdict());
                    }
                return null;
            }

            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = smooth(g0);
                int w = getWidth(), h = getHeight();
                title(g, "When she says X%, does it happen X% of the time?",
                        "Each dot is one band of forecasts. On the diagonal = perfectly calibrated.", 16, 24);

                int left = 58, right = 24, top = 64, bottom = 74;
                int size = Math.max(60, Math.min(w - left - right, h - top - bottom));
                int x0 = left + (w - left - right - size) / 2, y0 = top, x1 = x0 + size, y1 = y0 + size;
                Font small = UIManager.getFont("Label.font").deriveFont(11f);
                g.setFont(small);

                for (int t = 0; t <= 100; t += 20) {
                    int x = x0 + size * t / 100, y = y1 - size * t / 100;
                    g.setColor(GRID);
                    g.drawLine(x0, y, x1, y);
                    g.drawLine(x, y0, x, y1);
                    g.setColor(MUTED);
                    textRight(g, t + "%", x0 - 6, y + 4);
                    textCentre(g, t + "%", x, y1 + 16);
                }
                g.setColor(AXIS);
                g.setStroke(DASHED);
                g.drawLine(x0, y1, x1, y0);
                g.setStroke(new BasicStroke(1f));
                g.setColor(MUTED);
                g.drawString("happens more often than forecast", x0 + 8, y0 + 16);
                textRight(g, "happens less often than forecast", x1 - 8, y1 - 10);

                // axis titles
                textCentre(g, "Forecast probability", (x0 + x1) / 2, y1 + 34);
                Graphics2D gr = (Graphics2D) g.create();
                gr.rotate(-Math.PI / 2);
                textCentre(gr, "How often it came true", -(y0 + y1) / 2, x0 - 40);
                gr.dispose();

                // dots, joined, with a 90% range for the true rate behind each
                int maxN = a.bins().stream().mapToInt(Bin::n).max().orElse(1);
                hits.clear();
                java.awt.geom.Path2D line = new java.awt.geom.Path2D.Double();
                boolean first = true;
                for (int i = 0; i < a.bins().size(); i++) {
                    Bin b = a.bins().get(i);
                    if (b.n() == 0) continue;
                    double px = x0 + size * b.avgForecast(), py = y1 - size * b.observed();
                    if (first) line.moveTo(px, py); else line.lineTo(px, py);
                    first = false;
                }
                g.setColor(new Color(0x2563eb & 0xffffff | 0x66000000, true));
                g.setStroke(new BasicStroke(1.5f));
                g.draw(line);
                for (int i = 0; i < a.bins().size(); i++) {
                    Bin b = a.bins().get(i);
                    if (b.n() == 0) continue;
                    double px = x0 + size * b.avgForecast(), py = y1 - size * b.observed();
                    double[] ci = wilson(b.observed(), b.n(), 1.645);
                    g.setColor(CASSANDRA);
                    g.setStroke(new BasicStroke(1.5f));
                    int ylo = (int) (y1 - size * ci[0]), yhi = (int) (y1 - size * ci[1]);
                    g.drawLine((int) px, ylo, (int) px, yhi);
                    g.drawLine((int) px - 4, ylo, (int) px + 4, ylo);
                    g.drawLine((int) px - 4, yhi, (int) px + 4, yhi);
                    double r = 4 + 9 * Math.sqrt(b.n() / (double) maxN);
                    java.awt.geom.Ellipse2D dot = new java.awt.geom.Ellipse2D.Double(px - r, py - r, 2 * r, 2 * r);
                    if (b.n() >= 5) {
                        g.fill(dot);
                        g.setColor(Color.WHITE);
                        g.draw(dot);
                    } else {
                        g.setColor(Color.WHITE);
                        g.fill(dot);
                        g.setColor(CASSANDRA);
                        g.draw(dot);
                    }
                    // count label under the bar (above it if that would fall off the chart), clear of neighbours
                    g.setColor(INK);
                    int below = (int) Math.max(ylo, py + r) + 14, above = (int) Math.min(yhi, py - r) - 6;
                    textCentre(g, "n=" + b.n(), (int) px, below <= y1 - 4 ? below : above);
                    hits.add(new double[]{px, py, r, i});
                }
                g.setColor(MUTED);
                g.drawString("Dot size = number of predictions. Bars = 90% range for the true rate. "
                        + "Hollow = fewer than 5.", 16, h - 12);
                g.dispose();
            }
        }

        // ---------------------------------------------------------------- Brier over time

        /** Weekly Brier score on the comparable predictions: Cassandra, her raw guesses, and the base rate. */
        @SuppressWarnings("serial")
        static final class BrierChart extends JComponent {
            final Accuracy a;
            final List<WeekRow> weeks;
            int[] xs = new int[0];

            BrierChart(Accuracy a) {
                this.a = a;
                this.weeks = a.weeks().stream().filter(w -> w.comparable() > 0).toList();
                setPreferredSize(new Dimension(480, 420));
                setToolTipText("");
            }

            @Override public String getToolTipText(java.awt.event.MouseEvent e) {
                for (int i = 0; i < xs.length; i++)
                    if (Math.abs(e.getX() - xs[i]) < 14) {
                        WeekRow w = weeks.get(i);
                        return String.format(Locale.ROOT, "<html><b>Week of %s</b><br>%d comparable predictions<br>"
                                + "Cassandra %.3f<br>before recalibration %.3f<br>base-rate forecaster %.3f</html>",
                                w.week(), w.comparable(), w.compBrier(), w.compRaw(), w.compBase());
                    }
                return null;
            }

            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = smooth(g0);
                int w = getWidth(), h = getHeight();
                title(g, "Brier score by week (lower is better)",
                        "Only predictions both could make, so they face the same questions.", 16, 24);
                Font small = UIManager.getFont("Label.font").deriveFont(11f);
                g.setFont(small);

                // legend
                int lx = 16, ly = 62;
                lx = legend(g, lx, ly, CASSANDRA, new BasicStroke(2.5f), "Cassandra");
                lx = legend(g, lx, ly, RAW, DASHED, "before recalibration");
                lx = legend(g, lx, ly, BASE, new BasicStroke(2f), "base rate");
                legend(g, lx, ly, COIN, DASHED, "coin flip");

                if (weeks.size() < 2) {
                    g.setColor(MUTED);
                    g.drawString("Not enough weeks of comparable predictions yet to draw a trend.", 16, 110);
                    g.dispose();
                    return;
                }

                int left = 52, right = 28, top = 84, bottom = 52;
                int x0 = left, x1 = w - right, y0 = top, y1 = h - bottom;
                double max = 0.25;
                for (WeekRow r : weeks) max = Math.max(max, Math.max(r.compBrier(), Math.max(r.compRaw(), r.compBase())));
                double ymax = Math.ceil(max / 0.05) * 0.05 + 0.0001;

                for (double t = 0; t <= ymax; t += 0.05) {
                    int y = (int) (y1 - (y1 - y0) * t / ymax);
                    g.setColor(GRID);
                    g.drawLine(x0, y, x1, y);
                    g.setColor(MUTED);
                    textRight(g, String.format(Locale.ROOT, "%.2f", t), x0 - 6, y + 4);
                }
                int n = weeks.size();
                xs = new int[n];
                DateTimeFormatter fmt = DateTimeFormatter.ofPattern("d MMM", Locale.UK);
                int every = Math.max(1, (int) Math.ceil(n / 8.0));
                for (int i = 0; i < n; i++) {
                    xs[i] = x0 + 10 + (int) ((x1 - x0 - 20) * (n == 1 ? 0.5 : i / (double) (n - 1)));
                    if (i % every == 0 || i == n - 1) {
                        g.setColor(MUTED);
                        textCentre(g, weeks.get(i).week().format(fmt), xs[i], y1 + 16);
                    }
                }
                g.setColor(MUTED);
                textCentre(g, "Week starting (by date graded)", (x0 + x1) / 2, y1 + 36);

                int coinY = (int) (y1 - (y1 - y0) * 0.25 / ymax);
                g.setColor(COIN);
                g.setStroke(DASHED);
                g.drawLine(x0, coinY, x1, coinY);

                series(g, i -> weeks.get(i).compRaw(), RAW, DASHED, false, ymax, y0, y1);
                series(g, i -> weeks.get(i).compBase(), BASE, new BasicStroke(2f), true, ymax, y0, y1);
                series(g, i -> weeks.get(i).compBrier(), CASSANDRA, new BasicStroke(2.5f), true, ymax, y0, y1);
                g.dispose();
            }

            void series(Graphics2D g, java.util.function.IntToDoubleFunction v, Color c, BasicStroke s, boolean dots,
                        double ymax, int y0, int y1) {
                java.awt.geom.Path2D p = new java.awt.geom.Path2D.Double();
                for (int i = 0; i < xs.length; i++) {
                    double y = y1 - (y1 - y0) * v.applyAsDouble(i) / ymax;
                    if (i == 0) p.moveTo(xs[i], y); else p.lineTo(xs[i], y);
                }
                g.setColor(c);
                g.setStroke(s);
                g.draw(p);
                if (dots)
                    for (int i = 0; i < xs.length; i++) {
                        double y = y1 - (y1 - y0) * v.applyAsDouble(i) / ymax;
                        g.fill(new java.awt.geom.Ellipse2D.Double(xs[i] - 3.5, y - 3.5, 7, 7));
                    }
            }

            static int legend(Graphics2D g, int x, int y, Color c, BasicStroke s, String label) {
                g.setColor(c);
                g.setStroke(s);
                g.drawLine(x, y - 4, x + 22, y - 4);
                g.setStroke(new BasicStroke(1f));
                g.setColor(INK);
                g.drawString(label, x + 28, y);
                return x + 28 + g.getFontMetrics().stringWidth(label) + 18;
            }
        }

        static final String VERSION = "2.0";
        static final String REPO = "http://github.com/RDMachinery/Cassandra";

        void showAbout() {
            JEditorPane text = new JEditorPane("text/html", "<html><body style='font-family: sans-serif'>"
                    + "<b>Cassandra Version " + VERSION + ".</b><br><br>"
                    + "Written by Mario Gianota.<br>"
                    + "Copyright 2026. MIT License.<br><br>"
                    + "Suggestions, feature requests and bug reports are welcome.<br>"
                    + "<a href='" + REPO + "'>" + REPO + "</a>."
                    + "</body></html>");
            text.setEditable(false);
            text.setOpaque(false);
            text.addHyperlinkListener(e -> {
                if (e.getEventType() == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) browse(REPO);
            });
            JOptionPane.showMessageDialog(frame, text, "About Cassandra", JOptionPane.INFORMATION_MESSAGE);
        }

        static void open(Path dir) {
            if (GraphicsEnvironment.isHeadless()) {
                System.out.println("--gui needs a display. Run it on your desktop, or use --report in a terminal.");
                return;
            }
            System.setProperty("apple.laf.useScreenMenuBar", "true"); // on a Mac, menus go in the top menu bar
            System.setProperty("apple.awt.application.name", "Cassandra");
            SwingUtilities.invokeLater(() -> {
                try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
                catch (Exception ignored) { /* keep the default look */ }
                new Gui(dir).frame.setVisible(true);
            });
        }
    }
}
