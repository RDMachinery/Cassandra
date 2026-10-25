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

    static final int WRAP = 90;

    // Settings: these defaults apply unless the data folder's cassandra.properties says otherwise
    // (see Settings and loadSettings). The window's Settings tab edits that file.
    static int MIN_ARTICLES = 8;               // fewer headlines than this and a run stops without saving
    static int HISTORY_DAYS = 14;              // how far back the trend fit looks
    static int MIN_FOR_CALIBRATION = 5;        // resolved predictions needed before recalibrating
    static int GRACE_DAYS = 2;                 // a prediction not graded within this window is void
    static int PREDICTED_STORIES = 3;          // how many of the leading stories get predictions
    static double RANK_PENALTY = 0.07;         // TOP5 prior is lowered by this much per rank below #1
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
        String dataArg = null, replayArg = null;
        boolean asof = false, accuracy = false, gui = false;
        List<String> feeds = new ArrayList<>();

        for (String a : args) {
            if (a.equals("--demo")) demo = true;
            else if (a.startsWith("--replay=")) replayArg = a.substring(9);
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
        if (replayArg != null) {
            replayCommand(Paths.get(replayArg), dataArg == null ? null : Paths.get(dataArg));
            return;
        }
        if (asof && !demo)
            System.out.println("Warning: --asof is meant for --demo/backtests; live headlines are dated in real time.\n");

        Path dir = Paths.get(dataArg != null ? dataArg : demo ? "cassandra-demo-data" : "cassandra-data");
        if (gui) { Gui.open(dir, demo); return; }
        Settings settings = loadSettings(dir);   // the folder's cassandra.properties, or the defaults
        settings.apply();
        if (accuracy) { accuracyReport(dir); return; }
        List<Article> articles;

        if (demo) {
            articles = demoArticles();
            System.out.println("[demo mode: synthetic headlines, not real news]\n");
        } else {
            articles = fetchAll(feeds.isEmpty() ? settings.feeds() : feeds);   // command-line feeds win
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
                  --replay=DIR    re-run Cassandra over DIR's archived headlines into a fresh folder
                                  (--data=OUT, default DIR-replay), then report its accuracy
                """);
    }

    // ------------------------------------------------------------------ fetching

    /** Fetch every feed, reporting each one, and return all their headlines (duplicates included). */
    static List<Article> fetchAll(List<String> feeds) {
        List<Article> articles = new ArrayList<>();
        for (String url : feeds) {
            try {
                List<Article> got = fetchFeed(url);
                System.out.printf("Fetched %2d items from %s%n", got.size(), url);
                articles.addAll(got);
            } catch (Exception e) {
                System.out.printf("Could not read %s (%s)%n", url, e.getMessage());
            }
        }
        System.out.println();
        return articles;
    }

    static List<Article> fetchFeed(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Cassandra/2.0 (https://github.com/RDMachinery/Cassandra)")
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
        RunResult r;
        try {
            r = forecastRun(articles, dir, log);
        } catch (BusyException e) {
            System.out.println(e.getMessage());
            return;
        }
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
        return forecastRun(articles, dir, log, log);
    }

    /** As above; archive says whether a saving run also archives its headlines (replays don't). */
    static RunResult forecastRun(List<Article> articles, Path dir, boolean log, boolean archive) throws IOException {
        // saving runs hold the folder's lock from reading to writing, so two runs can't overwrite each other
        return log ? withLock(dir, () -> forecastRunUnlocked(articles, dir, true, archive))
                : forecastRunUnlocked(articles, dir, false, false);
    }

    static RunResult forecastRunUnlocked(List<Article> articles, Path dir, boolean log, boolean archive)
            throws IOException {
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
        if (archive) saveArchive(dir, articles);
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

    // ------------------------------------------------------------------ settings (cassandra.properties)

    static final String CONFIG = "cassandra.properties";

    /** Everything a data folder can configure. Each run loads its folder's settings first. */
    record Settings(List<String> feeds, int minArticles, int predictedStories, double rankPenalty,
                    int historyDays, int minForCalibration, int graceDays) {
        static Settings defaults() { return new Settings(DEFAULT_FEEDS, 8, 3, 0.07, 14, 5, 2); }

        /** Use these settings for the runs that follow. */
        void apply() {
            MIN_ARTICLES = minArticles;
            PREDICTED_STORIES = predictedStories;
            RANK_PENALTY = rankPenalty;
            HISTORY_DAYS = historyDays;
            MIN_FOR_CALIBRATION = minForCalibration;
            GRACE_DAYS = graceDays;
        }
    }

    /** The folder's settings, or the defaults for anything it does not set (or sets out of range). */
    static Settings loadSettings(Path dir) {
        Settings d = Settings.defaults();
        Path f = dir.resolve(CONFIG);
        if (!Files.exists(f)) return d;
        Properties p = new Properties();
        try (var in = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            p.load(in);
        } catch (IOException e) {
            System.out.println("Could not read " + f + " (" + e.getMessage() + "); using default settings.");
            return d;
        }
        List<String> feeds = p.stringPropertyNames().stream().filter(k -> k.matches("feed\\.\\d+"))
                .sorted(Comparator.comparingInt(k -> Integer.parseInt(k.substring(5))))
                .map(k -> p.getProperty(k).trim()).filter(u -> !u.isEmpty()).toList();
        return new Settings(feeds.isEmpty() ? d.feeds() : feeds,
                (int) setting(p, "min.articles", d.minArticles(), 3, 50),
                (int) setting(p, "predicted.stories", d.predictedStories(), 1, 5),
                setting(p, "rank.penalty", d.rankPenalty(), 0, 0.2),
                (int) setting(p, "history.days", d.historyDays(), 3, 60),
                (int) setting(p, "calibration.min", d.minForCalibration(), 2, 50),
                (int) setting(p, "grace.days", d.graceDays(), 0, 7));
    }

    static double setting(Properties p, String key, double def, double lo, double hi) {
        String v = p.getProperty(key);
        if (v == null) return def;
        try {
            double x = Double.parseDouble(v.trim());
            if (x >= lo && x <= hi) return x;
        } catch (NumberFormatException ignored) { /* reported below */ }
        System.out.println(CONFIG + ": ignoring " + key + "=" + v.trim() + " (must be a number from "
                + plain(lo) + " to " + plain(hi) + "); using " + plain(def) + ".");
        return def;
    }

    /** 0.07 rather than 0.07000, 2 rather than 2.0. */
    static String plain(double x) {
        return java.math.BigDecimal.valueOf(x).stripTrailingZeros().toPlainString();
    }

    static void saveSettings(Path dir, Settings s) throws IOException {
        Files.createDirectories(dir);
        List<String> out = new ArrayList<>(List.of(
                "# Cassandra settings for this data folder. Every run (including scheduled ones) reads this file.",
                "# Edit it here or in the window's Settings tab. Delete it to go back to the defaults.",
                "",
                "# News feeds (RSS 2.0). Feeds given on the command line take priority over these."));
        for (int i = 0; i < s.feeds().size(); i++) out.add("feed." + (i + 1) + "=" + s.feeds().get(i));
        out.addAll(List.of("",
                "# A run with fewer headlines than this stops without saving (3-50)",
                "min.articles=" + s.minArticles(),
                "# How many of the day's leading stories get predictions (1-5)",
                "predicted.stories=" + s.predictedStories(),
                "# Starting 'still in the top 5' probability is lowered by this much per rank below first (0-0.2)",
                "rank.penalty=" + String.format(Locale.ROOT, "%.2f", s.rankPenalty()),
                "# Days of history the coverage trend is fitted over (3-60)",
                "history.days=" + s.historyDays(),
                "# Graded results a prediction type needs before its probabilities are recalibrated (2-50)",
                "calibration.min=" + s.minForCalibration(),
                "# Days after its due date that a prediction can still be graded; later, it is voided (0-7)",
                "grace.days=" + s.graceDays()));
        writeAtomically(dir.resolve(CONFIG), out);
    }

    // ------------------------------------------------------------------ headline archive and replay

    static final String ARCHIVE = "archive";

    /** One archived run: its date, the moment it ran (article ages are measured from it), and its headlines. */
    record ArchiveDay(LocalDate date, Instant ran, List<Article> articles) {}

    /**
     * Keep the exact headlines a saving run analysed, in archive/YYYY-MM-DD.tsv: a "#ran" line with the run's
     * time, then one line per headline (source, published, title, body, link). Re-running a day replaces it.
     */
    static void saveArchive(Path dir, List<Article> articles) throws IOException {
        Path a = dir.resolve(ARCHIVE);
        Files.createDirectories(a);
        List<String> out = new ArrayList<>();
        out.add("#ran\t" + NOW);
        for (Article x : articles)
            out.add(String.join("\t", cell(x.source()), x.published().toString(), cell(x.title()), cell(x.body()),
                    cell(x.link())));
        writeAtomically(a.resolve(TODAY + ".tsv"), out);
    }

    /** The folder's archived days, oldest first. */
    static List<Path> archiveFiles(Path dir) {
        Path a = dir.resolve(ARCHIVE);
        if (!Files.isDirectory(a)) return List.of();
        try (var s = Files.list(a)) {
            return s.filter(p -> p.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}\\.tsv")).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    static ArchiveDay loadArchiveDay(Path f) throws IOException {
        LocalDate date = LocalDate.parse(f.getFileName().toString().substring(0, 10));
        Instant ran = date.atTime(12, 0).toInstant(ZoneOffset.UTC);
        List<Article> articles = new ArrayList<>();
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            String[] c = line.split("\t", -1);
            if (c[0].equals("#ran")) { ran = Instant.parse(c[1]); continue; }
            if (c.length < 5) continue;
            articles.add(new Article(c[0], c[2], c[3], Instant.parse(c[1]), c[4]));
        }
        return new ArchiveDay(date, ran, articles);
    }

    /**
     * Re-run the forecaster over src's archived days, in date order, saving into out with the given settings.
     * Each day runs as it originally did (same date, same run time, same headlines), so with the original
     * settings a replay reproduces the original track record. Returns the number of days run.
     */
    static int replay(Path src, Path out, Settings s, java.util.function.Consumer<String> say,
                      java.util.function.BooleanSupplier stop) throws IOException {
        List<Path> files = archiveFiles(src);
        LocalDate today = TODAY;
        Instant now = NOW;
        s.apply();
        int n = 0;
        try {
            for (Path f : files) {
                if (stop.getAsBoolean()) break;
                ArchiveDay d = loadArchiveDay(f);
                TODAY = d.date();
                NOW = d.ran();
                FORMS.clear();
                String day = d.date().format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK));
                if (d.articles().size() < MIN_ARTICLES) {
                    say.accept(day + ": " + d.articles().size() + " headlines, fewer than the " + MIN_ARTICLES
                            + " needed; skipped.");
                    continue;
                }
                RunResult r = forecastRun(d.articles(), out, true, false);
                say.accept(r == null ? day + ": no story had enough coverage."
                        : String.format(Locale.ROOT, "%s: %d headlines, top story \"%s\"; %d predictions made, %d "
                        + "graded.", day, r.headlines(), r.lead().label(), r.added(), r.gradedNow().size()));
                n++;
            }
        } finally {
            TODAY = today;
            NOW = now;
        }
        return n;
    }

    /** One line summing up a track record, for comparing two of them. */
    static String compareLine(Accuracy a) {
        if (a == null || a.graded().isEmpty()) return "nothing graded yet.";
        String s = String.format(Locale.ROOT, "%d graded, %.0f%% came true against an average forecast of %.0f%%, "
                + "Brier %.3f, skill vs base rate ", a.graded().size(),
                100 * a.graded().stream().mapToDouble(Cassandra::outcome).average().orElse(0),
                100 * a.graded().stream().mapToDouble(Pred::p).average().orElse(0), a.bCass());
        return s + (a.ci() == null ? num(a.skill(), "%+.2f") + " (provisional)"
                : String.format(Locale.ROOT, "%+.2f (90%% interval %+.2f to %+.2f)", a.skill(), a.ci()[0], a.ci()[1]))
                + ".";
    }

    static boolean holdsTrackRecord(Path dir) {
        return Files.exists(dir.resolve("history.tsv")) || Files.exists(dir.resolve("predictions.tsv"));
    }

    /** --replay=SRC [--data=OUT] */
    static void replayCommand(Path src, Path out) throws IOException {
        List<Path> files = archiveFiles(src);
        if (files.isEmpty()) {
            System.out.println("No archived headlines in " + src.resolve(ARCHIVE).toAbsolutePath().normalize()
                    + ". Cassandra archives the headlines of every saving run from version 2.0 on, so there is "
                    + "nothing to replay yet.");
            return;
        }
        Path s = src.toAbsolutePath().normalize();
        if (out == null) out = s.resolveSibling(s.getFileName() + "-replay");
        if (out.toAbsolutePath().normalize().equals(s) || holdsTrackRecord(out)) {
            System.out.println(wrap("Not replaying into " + out.toAbsolutePath().normalize() + ": it already holds a "
                    + "track record, and replaying into it would mix two. Delete it, or choose an empty folder with "
                    + "--data=DIR.", ""));
            return;
        }
        boolean own = Files.exists(out.resolve(CONFIG));
        Settings settings = own ? loadSettings(out) : loadSettings(src);
        String bar = "=".repeat(WRAP);
        System.out.println(bar);
        System.out.println(" CASSANDRA - replay of " + files.size() + " archived days");
        System.out.println(bar);
        System.out.println("From: " + s.resolve(ARCHIVE));
        System.out.println("Into: " + out.toAbsolutePath().normalize());
        System.out.println(wrap("Settings: " + (own ? "from " + out.resolve(CONFIG).toAbsolutePath().normalize()
                : (Files.exists(src.resolve(CONFIG)) ? "copied from " + s.resolve(CONFIG) : "the defaults")
                + ". To test other settings, put a cassandra.properties in the output folder first."), ""));
        System.out.println();
        int n = replay(src, out, settings, System.out::println, () -> false);
        if (own) { /* the output folder already has its settings file */ }
        else if (Files.exists(src.resolve(CONFIG))) saveSettings(out, settings);   // keep a record of what was used
        System.out.println("\nReplayed " + n + " of " + files.size() + " days.\n");
        Accuracy a = loadAccuracy(out);
        if (a != null) printAccuracy(a);
        Accuracy orig = holdsTrackRecord(src) ? loadAccuracy(src) : null;
        if (orig != null) {
            System.out.println("\nCOMPARED WITH THE ORIGINAL RUNS");
            System.out.println(wrap("Original (" + s + "): " + compareLine(orig), "  "));
            System.out.println(wrap("This replay: " + compareLine(a), "  "));
            System.out.println(wrap("With the same settings and a complete archive these match exactly. With different "
                    + "settings, compare the skill scores: if their intervals overlap, the difference may be luck.", "  "));
        }
    }

    // ------------------------------------------------------------------ run lock

    static final String LOCK = "cassandra.lock";

    /** Raised when another run (scheduled, or from the window) is saving to the same folder. */
    @SuppressWarnings("serial")
    static final class BusyException extends IOException {
        BusyException() {
            super("Another Cassandra run is saving to this data folder right now, so this run stopped without "
                    + "saving. Try again in a moment.");
        }
    }

    interface IOWork<T> { T run() throws IOException; }

    /** Run work while holding the data folder's lock (an OS file lock on cassandra.lock). */
    static <T> T withLock(Path dir, IOWork<T> work) throws IOException {
        Files.createDirectories(dir);
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(dir.resolve(LOCK),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
             java.nio.channels.FileLock lock = ch.tryLock()) {
            if (lock == null) throw new BusyException();
            return work.run();
        } catch (java.nio.channels.OverlappingFileLockException e) {
            throw new BusyException();   // held by another run in this same program
        }
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
        final Ledger ledger = new Ledger(frame);
        final StoryHistory stories = new StoryHistory(this);
        final JTabbedPane tabs = new JTabbedPane();
        final JButton choose = new JButton("Choose…");
        Path dir;
        Path liveDir, demoDir;    // the folders for each mode; demo runs never write to the live folder
        boolean demoMode;
        final RunPanel runPanel;
        final SettingsPanel settingsPanel;
        long lastModified = Long.MIN_VALUE;
        String selectedTerm;   // the story picked on the Today tab, kept across reloads

        /** Everything the window shows, read from the data folder in one go. Any part may be missing. */
        record Loaded(Accuracy accuracy, Latest latest, List<HistRow> history, Map<String, Pred> preds,
                      Settings settings) {}

        Gui(Path dir) { this(dir, false); }

        Gui(Path dir, boolean demo) {
            this.dir = dir;
            this.demoMode = demo;
            this.liveDir = demo ? Paths.get("cassandra-data") : dir;
            this.demoDir = demo ? dir : Paths.get("cassandra-demo-data");
            this.runPanel = new RunPanel(this);
            this.settingsPanel = new SettingsPanel(this);
            frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);

            JPanel bar = new JPanel(new BorderLayout(12, 0));
            bar.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
            JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            left.add(new JLabel("Data folder:"));
            folderLabel.setFont(folderLabel.getFont().deriveFont(Font.BOLD));
            left.add(folderLabel);
            choose.addActionListener(e -> chooseFolder());
            JButton reload = new JButton("Reload");
            reload.addActionListener(e -> reload());
            left.add(choose);
            left.add(reload);
            bar.add(left, BorderLayout.WEST);
            status.setForeground(MUTED);
            bar.add(status, BorderLayout.EAST);

            tabs.addTab("Today", todayHost);
            tabs.addTab("Accuracy", accuracyHost);
            tabs.addTab("Predictions", ledger);
            tabs.addTab("Story history", stories);
            tabs.addTab("Run", runPanel);
            tabs.addTab("Settings", settingsPanel);
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
            for (String f : List.of("predictions.tsv", LATEST, CONFIG))
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
                if (demoMode) demoDir = dir; else liveDir = dir;
                reload();
            }
        }

        /** Switch between live and demo mode; each has its own data folder. */
        void setDemoMode(boolean demo) {
            if (demo == demoMode) return;
            demoMode = demo;
            dir = demo ? demoDir : liveDir;
            selectedTerm = null;
            reload();
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
                            loadHistory(from), byId, loadSettings(from));
                }
                @Override protected void done() {
                    Loaded l;
                    try { l = get(); } catch (Exception ex) {
                        l = new Loaded(null, null, List.of(), Map.of(), Settings.defaults());
                    }
                    if (!from.equals(dir)) return;   // the folder changed while loading; a newer reload follows
                    frame.setTitle(demoMode ? "Cassandra (demo data)" : "Cassandra");
                    runPanel.setData(from, l.history(), l.preds().values());
                    settingsPanel.setData(from, l.settings());
                    show(todayHost, todayView(l));
                    show(accuracyHost, accuracyView(l.accuracy()));
                    ledger.setData(l.preds().values());
                    stories.setData(l.history(), l.preds().values(), l.latest());
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
                        if (e.getClickCount() == 2) showStory(card.story.term());   // double-click: its history
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
            return typeLabel(key) + "  (" + key + ")";
        }

        /** A prediction type ("TOP5@3") in words, without the code. */
        static String typeLabel(String key) {
            String[] k = key.split("@");
            String what = switch (k[0]) {
                case "TOP5" -> "Still in the top 5";
                case "SHARE_UP" -> "Larger share of headlines";
                case "TONE_PERSIST" -> "Tone stays the same";
                default -> k[0];
            };
            return what + " after " + (k.length > 1 ? k[1] : "?") + " days";
        }

        // ---------------------------------------------------------------- Story history tab

        /** Open the Story history tab on one story. */
        void showStory(String term) {
            stories.select(term);
            tabs.setSelectedComponent(stories);
        }

        /** Open the Predictions tab showing only one story's predictions. */
        void showInLedger(String label) {
            ledger.status.setSelectedIndex(0);
            ledger.type.setSelectedIndex(0);
            ledger.search.setText(label);
            tabs.setSelectedComponent(ledger);
        }

        /** One story across all runs: when it was in the top 10, how high, and how many predictions it drew. */
        record StoryStat(String term, String label, LocalDate first, LocalDate last, int days, int bestRank,
                         int predictions, int graded, int hits) {}

        /**
         * Pick any story Cassandra has tracked and see its share of headlines and rank over time, with the
         * predictions made about it on the same timeline. Built once; selection and search survive reloads.
         */
        @SuppressWarnings("serial")
        static final class StoryHistory extends JPanel {
            static final String[] COLS = {"Story", "Days", "Best", "Last seen", "Predictions"};
            static final DateTimeFormatter D = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK);

            final Gui gui;
            List<StoryStat> stats = new ArrayList<>();
            List<HistRow> history = List.of();
            List<Pred> preds = List.of();
            String selected;

            final javax.swing.table.AbstractTableModel model = new javax.swing.table.AbstractTableModel() {
                @Override public int getRowCount() { return stats.size(); }
                @Override public int getColumnCount() { return COLS.length; }
                @Override public String getColumnName(int c) { return COLS[c]; }
                @Override public Class<?> getColumnClass(int c) {
                    return c == 0 ? String.class : c == 3 ? LocalDate.class : Integer.class;
                }
                @Override public Object getValueAt(int r, int c) {
                    StoryStat s = stats.get(r);
                    return switch (c) {
                        case 0 -> s.label();
                        case 1 -> s.days();
                        case 2 -> s.bestRank();
                        case 3 -> s.last();
                        default -> s.predictions();
                    };
                }
            };
            final JTable list = new JTable(model);
            final javax.swing.table.TableRowSorter<javax.swing.table.TableModel> sorter =
                    new javax.swing.table.TableRowSorter<>(model);
            final JTextField search = new JTextField(14);
            final JPanel detail = new JPanel(new BorderLayout());
            final java.awt.CardLayout cards = new java.awt.CardLayout();
            final JPanel body = new JPanel(cards);
            boolean updating;

            StoryHistory(Gui gui) {
                this.gui = gui;
                setLayout(new BorderLayout());
                setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

                // left: searchable list of every story that has been in the top 10
                list.setRowSorter(sorter);
                list.setRowHeight(24);
                list.setShowVerticalLines(false);
                list.setGridColor(GRID);
                list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
                list.getTableHeader().setReorderingAllowed(false);
                int[] widths = {150, 50, 50, 80, 80};
                for (int c = 0; c < widths.length; c++) list.getColumnModel().getColumn(c).setPreferredWidth(widths[c]);
                javax.swing.table.DefaultTableCellRenderer cell = new javax.swing.table.DefaultTableCellRenderer() {
                    @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel,
                                                                             boolean focus, int r, int c) {
                        Object shown = v instanceof LocalDate d ? d.format(DateTimeFormatter.ofPattern("d MMM", Locale.UK))
                                : c == 2 ? "#" + v : v;
                        super.getTableCellRendererComponent(t, shown, sel, false, r, c);
                        setHorizontalAlignment(c == 0 ? SwingConstants.LEFT : SwingConstants.RIGHT);
                        setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
                        return this;
                    }
                };
                for (int c = 0; c < COLS.length; c++) list.getColumnModel().getColumn(c).setCellRenderer(cell);
                sorter.setSortKeys(List.of(new RowSorter.SortKey(3, SortOrder.DESCENDING),
                        new RowSorter.SortKey(1, SortOrder.DESCENDING)));
                list.getSelectionModel().addListSelectionListener(e -> {
                    if (updating || e.getValueIsAdjusting()) return;
                    int r = list.getSelectedRow();
                    if (r >= 0) {
                        selected = stats.get(list.convertRowIndexToModel(r)).term();
                        showDetail();
                    }
                });
                search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                    @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { refilter(); }
                    @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { refilter(); }
                    @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { refilter(); }
                });

                JPanel left = new JPanel(new BorderLayout(0, 8));
                JPanel find = new JPanel(new BorderLayout(6, 0));
                find.add(new JLabel("Find a story:"), BorderLayout.WEST);
                find.add(search, BorderLayout.CENTER);
                left.add(find, BorderLayout.NORTH);
                JScrollPane sp = new JScrollPane(list);
                sp.getViewport().setBackground(Color.WHITE);
                left.add(sp, BorderLayout.CENTER);
                left.add(wrapped("Every story that has been in the top 10 on at least one run. Days: runs on which "
                        + "it was in the top 10. Best: its highest rank.", MUTED),
                        BorderLayout.SOUTH);
                left.setPreferredSize(new Dimension(390, 100));

                JPanel split = new JPanel(new BorderLayout(12, 0));
                split.add(left, BorderLayout.WEST);
                split.add(detail, BorderLayout.CENTER);
                body.add(split, "stories");
                body.add(message("No story history in this folder yet.",
                        "Each run records the day's top 10 stories in history.tsv. They appear here after the first run."),
                        "empty");
                add(body, BorderLayout.CENTER);
            }

            void setData(List<HistRow> hist, Collection<Pred> all, Latest latest) {
                history = hist;
                preds = new ArrayList<>(all);
                Map<String, String> labels = new HashMap<>();
                preds.stream().sorted(Comparator.comparing(Pred::made)).forEach(p -> labels.put(p.term(), p.label()));
                if (latest != null) for (SnapStory s : latest.stories()) labels.put(s.term(), s.label());

                Map<String, List<HistRow>> byTerm = new TreeMap<>();
                for (HistRow r : hist) byTerm.computeIfAbsent(r.term(), k -> new ArrayList<>()).add(r);
                List<StoryStat> out = new ArrayList<>();
                for (var e : byTerm.entrySet()) {
                    List<HistRow> rs = e.getValue();
                    List<Pred> ps = preds.stream().filter(p -> p.term().equals(e.getKey())).toList();
                    out.add(new StoryStat(e.getKey(), labels.getOrDefault(e.getKey(), e.getKey()),
                            rs.stream().map(HistRow::date).min(Comparator.naturalOrder()).orElseThrow(),
                            rs.stream().map(HistRow::date).max(Comparator.naturalOrder()).orElseThrow(),
                            (int) rs.stream().map(HistRow::date).distinct().count(),
                            rs.stream().mapToInt(HistRow::rank).min().orElse(10), ps.size(),
                            (int) ps.stream().filter(Pred::graded).count(),
                            (int) ps.stream().filter(p -> p.outcome().equals("TRUE")).count()));
                }
                if (selected == null && latest != null) selected = latest.stories().get(0).term();
                updating = true;
                stats = out;
                model.fireTableDataChanged();
                updating = false;
                cards.show(body, stats.isEmpty() ? "empty" : "stories");
                if (stats.stream().noneMatch(s -> s.term().equals(selected)))
                    selected = stats.isEmpty() ? null : stats.get(list.convertRowIndexToModel(0)).term();
                select(selected);
            }

            /** Select a story in the list (clearing the search if it hides it) and show its history. */
            void select(String term) {
                if (term == null) return;
                selected = term;
                int m = -1;
                for (int i = 0; i < stats.size(); i++) if (stats.get(i).term().equals(term)) m = i;
                if (m < 0) return;
                if (list.convertRowIndexToView(m) < 0) search.setText("");
                int v = list.convertRowIndexToView(m);
                updating = true;
                list.getSelectionModel().setSelectionInterval(v, v);
                updating = false;
                list.scrollRectToVisible(list.getCellRect(v, 0, true));
                showDetail();
            }

            void refilter() {
                String q = search.getText().trim().toLowerCase(Locale.ROOT);
                sorter.setRowFilter(q.isEmpty() ? null : new RowFilter<>() {
                    @Override public boolean include(Entry<? extends javax.swing.table.TableModel, ? extends Integer> e) {
                        StoryStat s = stats.get(e.getIdentifier());
                        return (s.label() + " " + s.term()).toLowerCase(Locale.ROOT).contains(q);
                    }
                });
                // keep the selection highlighted if it is still listed
                int m = -1;
                for (int i = 0; i < stats.size(); i++) if (stats.get(i).term().equals(selected)) m = i;
                int v = m < 0 ? -1 : list.convertRowIndexToView(m);
                updating = true;
                if (v >= 0) list.getSelectionModel().setSelectionInterval(v, v); else list.clearSelection();
                updating = false;
            }

            void showDetail() {
                StoryStat s = stats.stream().filter(x -> x.term().equals(selected)).findFirst().orElse(null);
                if (s == null) return;
                List<Pred> ps = preds.stream().filter(p -> p.term().equals(s.term())).toList();

                JPanel p = new JPanel(new BorderLayout(0, 10));
                JPanel head = new JPanel(new BorderLayout(12, 4));
                JLabel title = new JLabel(s.label());
                title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
                title.setForeground(INK);
                head.add(title, BorderLayout.NORTH);
                String facts = s.days() == 1
                        ? String.format(Locale.ROOT, "In the top 10 on one run, %s, at rank #%d.", s.first().format(D), s.bestRank())
                        : String.format(Locale.ROOT, "In the top 10 on %d runs between %s and %s; best rank #%d.",
                        s.days(), s.first().format(D), s.last().format(D), s.bestRank());
                List<Pred> graded = ps.stream().filter(Pred::graded).toList();
                if (ps.isEmpty())
                    facts += " No predictions were made about it (only the top " + PREDICTED_STORIES + " stories get them).";
                else if (graded.isEmpty())
                    facts += String.format(Locale.ROOT, " %d prediction%s, none graded yet.", ps.size(), ps.size() == 1 ? "" : "s");
                else
                    facts += String.format(Locale.ROOT, " %d predictions, %d graded: %d came true (%.0f%%) against an "
                            + "average forecast of %.0f%%; Brier score %.3f.", ps.size(), graded.size(), s.hits(),
                            100.0 * s.hits() / graded.size(), 100 * graded.stream().mapToDouble(Pred::p).average().orElse(0),
                            brier(graded, Pred::p));
                head.add(wrapped(facts, INK), BorderLayout.CENTER);
                if (!ps.isEmpty()) {
                    JButton open = new JButton("Show its predictions");
                    open.setToolTipText("Open the Predictions tab filtered to this story");
                    open.addActionListener(e -> gui.showInLedger(s.label()));
                    JPanel bp = new JPanel(new BorderLayout());
                    bp.add(open, BorderLayout.NORTH);
                    head.add(bp, BorderLayout.EAST);
                }
                p.add(head, BorderLayout.NORTH);
                p.add(card(new StoryTimeline(s, history, ps)), BorderLayout.CENTER);
                Gui.show(detail, p);
            }
        }

        /**
         * One story over time, in three bands that share a date axis: its share of headlines, its rank (top 5
         * shaded), and the predictions made about it, one lane per type, each marked at its due date.
         */
        @SuppressWarnings("serial")
        static final class StoryTimeline extends JComponent {
            final StoryStat s;
            final Map<LocalDate, HistRow> rows = new HashMap<>();
            final List<LocalDate> runs;           // every run date in range, whether or not the story was in the top 10
            final List<Pred> preds;
            final List<String> lanes;
            final LocalDate from, to, lastRun;
            final List<Object[]> hits = new ArrayList<>(); // {x, y, tooltip}

            StoryTimeline(StoryStat s, List<HistRow> history, List<Pred> preds) {
                this.s = s;
                this.preds = preds;
                for (HistRow r : history) if (r.term().equals(s.term())) rows.put(r.date(), r);
                lastRun = history.stream().map(HistRow::date).max(Comparator.naturalOrder()).orElse(s.last());
                LocalDate start = s.first().minusDays(1);
                LocalDate end = preds.stream().map(Pred::due).max(Comparator.naturalOrder()).orElse(s.last());
                if (end.isBefore(s.last())) end = s.last();
                LocalDate endPad = end.plusDays(1);
                this.from = start;
                this.to = endPad;
                runs = history.stream().map(HistRow::date).filter(d -> !d.isBefore(start) && !d.isAfter(endPad))
                        .distinct().sorted().toList();
                lanes = preds.stream().map(Pred::key).distinct().sorted().toList();
                setPreferredSize(new Dimension(620, 520));
                setToolTipText("");
            }

            @Override public String getToolTipText(java.awt.event.MouseEvent e) {
                Object[] best = null;
                double bd = 9;
                for (Object[] h : hits) {
                    double d = Math.hypot(e.getX() - (double) h[0], e.getY() - (double) h[1]);
                    if (d < bd) { bd = d; best = h; }
                }
                return best == null ? null : (String) best[2];
            }

            static String laneLabel(String key) {
                String[] k = key.split("@");
                String what = switch (k[0]) {
                    case "TOP5" -> "Top 5";
                    case "SHARE_UP" -> "Share up";
                    case "TONE_PERSIST" -> "Tone holds";
                    default -> k[0];
                };
                return what + " · " + (k.length > 1 ? k[1] : "?") + "d";
            }

            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = smooth(g0);
                int w = getWidth(), h = getHeight();
                Font small = UIManager.getFont("Label.font").deriveFont(11f);
                Font bold = small.deriveFont(Font.BOLD, 12.5f);
                hits.clear();

                int left = 92, right = 24, top = 40, bottom = 30, gap = 44;   // band titles sit above each band
                int laneH = 24, lanesH = Math.max(1, lanes.size()) * laneH;
                int avail = h - top - bottom - lanesH - 2 * gap - 20;
                int shareH = (int) (avail * 0.6), rankH = avail - shareH;
                int x0 = left, x1 = w - right;
                int sTop = top, sBot = sTop + shareH;
                int rTop = sBot + gap, rBot = rTop + rankH;
                int lTop = rBot + gap + 20, lBot = lTop + lanesH;
                long span = Math.max(1, ChronoUnit.DAYS.between(from, to));
                java.util.function.ToDoubleFunction<LocalDate> X =
                        d -> x0 + (x1 - x0) * (double) ChronoUnit.DAYS.between(from, d) / span;

                // future (after the latest run): predictions there are still pending
                if (lastRun.isBefore(to)) {
                    int xl = (int) X.applyAsDouble(lastRun);
                    g.setColor(new Color(0xf6f7f9));
                    g.fillRect(xl, sTop - 8, x1 - xl, lBot - sTop + 8);
                    g.setColor(AXIS);
                    g.setStroke(DASHED);
                    g.drawLine(xl, sTop - 8, xl, lBot);
                    g.setStroke(new BasicStroke(1f));
                    g.setColor(MUTED);
                    g.setFont(small);
                    g.drawString("latest run", xl + 4, sTop - 12 + 10);
                }

                // ---- band 1: share of headlines
                double maxShare = rows.values().stream().mapToDouble(HistRow::share).max().orElse(0.1);
                double ymax = Math.min(1, Math.max(0.1, Math.ceil(maxShare * 1.15 / 0.1) * 0.1));
                java.util.function.DoubleUnaryOperator YS = v -> sBot - (sBot - sTop) * v / ymax;
                g.setFont(bold);
                g.setColor(INK);
                g.drawString("Share of headlines", 12, sTop - 16);
                g.setFont(small);
                g.setColor(MUTED);
                g.drawString("Dots: runs where it was in the top 10. Ticks on the axis: runs where it was not.",
                        12 + g.getFontMetrics(bold).stringWidth("Share of headlines") + 14, sTop - 16);
                double step = ymax > 0.5 ? 0.2 : 0.1;
                for (double t = 0; t <= ymax + 1e-9; t += step) {
                    int y = (int) YS.applyAsDouble(t);
                    g.setColor(GRID);
                    g.drawLine(x0, y, x1, y);
                    g.setColor(MUTED);
                    textRight(g, String.format(Locale.ROOT, "%.0f%%", t * 100), x0 - 6, y + 4);
                }
                plotSeries(g, X, d -> YS.applyAsDouble(rows.get(d).share()), sBot,
                        d -> String.format(Locale.ROOT, "%s: %.0f%% of headlines (%d of %d), rank #%d", d.format(DAY),
                                rows.get(d).share() * 100, rows.get(d).count(), rows.get(d).total(), rows.get(d).rank()));

                // ---- band 2: rank (1 at the top), top 5 shaded
                java.util.function.DoubleUnaryOperator YR = r -> rTop + (rBot - rTop) * (r - 1) / 9.0;
                g.setFont(bold);
                g.setColor(INK);
                g.drawString("Rank", 12, rTop - 16);
                g.setFont(small);
                g.setColor(new Color(0xeef3fd));
                g.fillRect(x0, (int) YR.applyAsDouble(0.5), x1 - x0, (int) (YR.applyAsDouble(5.5) - YR.applyAsDouble(0.5)));
                g.setColor(new Color(0x7a96d6));
                g.drawString("top 5", x0 + 6, (int) YR.applyAsDouble(1) + 4);
                for (int r : new int[]{1, 5, 10}) {
                    int y = (int) YR.applyAsDouble(r);
                    g.setColor(GRID);
                    g.drawLine(x0, y, x1, y);
                    g.setColor(MUTED);
                    textRight(g, "#" + r, x0 - 6, y + 4);
                }
                plotSeries(g, X, d -> YR.applyAsDouble(rows.get(d).rank()), rBot + 6,
                        d -> String.format(Locale.ROOT, "%s: rank #%d", d.format(DAY), rows.get(d).rank()));

                // ---- band 3: predictions, one lane per type, marked at the due date
                g.setFont(bold);
                g.setColor(INK);
                g.drawString("Predictions (at due date)", 8, lTop - 12);
                g.setFont(small);
                int lx = x1;
                String[][] legend = {{"void", "V"}, {"pending", "P"}, {"didn't happen", "F"}, {"came true", "T"}};
                for (String[] it : legend) {
                    int tw = g.getFontMetrics().stringWidth(it[0]);
                    lx -= tw;
                    g.setColor(MUTED);
                    g.drawString(it[0], lx, lTop - 12);
                    lx -= 14;
                    marker(g, it[1], lx + 5, lTop - 16);
                    lx -= 14;
                }
                if (lanes.isEmpty()) {
                    g.setColor(MUTED);
                    g.drawString("No predictions were made about this story.", x0, lTop + 16);
                }
                for (int i = 0; i < lanes.size(); i++) {
                    int y = lTop + i * laneH + laneH / 2;
                    g.setColor(GRID);
                    g.drawLine(x0, y, x1, y);
                    g.setColor(INK);
                    textRight(g, laneLabel(lanes.get(i)), x0 - 8, y + 4);
                    for (Pred p : preds) {
                        if (!p.key().equals(lanes.get(i))) continue;
                        double x = X.applyAsDouble(p.due());
                        String code = switch (p.outcome()) {
                            case "TRUE" -> "T";
                            case "FALSE" -> "F";
                            case "VOID" -> "V";
                            default -> "P";
                        };
                        marker(g, code, (int) x, y);
                        hits.add(new Object[]{x, (double) y, "<html><b>" + escape(statement(p)) + "</b><br>Forecast "
                                + pct(p.p()) + (Math.abs(p.p() - p.pRaw()) > 1e-9 ? " (before recalibration "
                                + pct(p.pRaw()) + ")" : "") + " · made " + p.made().format(DAY) + "<br>"
                                + outcomeText(p) + (p.graded() ? " (graded " + resolvedOn(p).format(DAY) + ")" : "")
                                + "</html>"});
                    }
                }

                // ---- shared date axis
                int every = Math.max(1, (int) Math.ceil(span / 9.0));
                DateTimeFormatter f = DateTimeFormatter.ofPattern("d MMM", Locale.UK);
                g.setFont(small);
                for (long d = 0; d <= span; d += every) {
                    LocalDate day = from.plusDays(d);
                    int x = (int) X.applyAsDouble(day);
                    g.setColor(MUTED);
                    textCentre(g, day.format(f), x, lBot + 18);
                    g.setColor(GRID);
                    g.drawLine(x, lBot + 2, x, lBot + 6);
                }
                g.dispose();
            }

            /**
             * Dots for days the story was in the top 10, joined where the runs were consecutive; a small grey tick
             * at the band's foot for runs where it was outside the top 10.
             */
            void plotSeries(Graphics2D g, java.util.function.ToDoubleFunction<LocalDate> X,
                            java.util.function.ToDoubleFunction<LocalDate> Y, int foot,
                            java.util.function.Function<LocalDate, String> tip) {
                g.setColor(CASSANDRA);
                g.setStroke(new BasicStroke(2f));
                for (int i = 1; i < runs.size(); i++) {
                    LocalDate a = runs.get(i - 1), b = runs.get(i);
                    if (rows.containsKey(a) && rows.containsKey(b))
                        g.draw(new java.awt.geom.Line2D.Double(X.applyAsDouble(a), Y.applyAsDouble(a),
                                X.applyAsDouble(b), Y.applyAsDouble(b)));
                }
                g.setStroke(new BasicStroke(1f));
                for (LocalDate d : runs) {
                    double x = X.applyAsDouble(d);
                    if (rows.containsKey(d)) {
                        double y = Y.applyAsDouble(d);
                        g.setColor(CASSANDRA);
                        g.fill(new java.awt.geom.Ellipse2D.Double(x - 3.5, y - 3.5, 7, 7));
                        hits.add(new Object[]{x, y, tip.apply(d)});
                    } else {
                        g.setColor(AXIS);
                        g.drawLine((int) x, foot - 4, (int) x, foot + 2);
                        hits.add(new Object[]{x, (double) foot, d.format(DAY) + ": not in the top 10"});
                    }
                }
            }

            /** T came true (filled green), F didn't happen (red cross), P pending (hollow), V void (grey dash). */
            static void marker(Graphics2D g, String code, int x, int y) {
                switch (code) {
                    case "T" -> {
                        g.setColor(GOOD);
                        g.fillOval(x - 5, y - 5, 10, 10);
                    }
                    case "F" -> {
                        g.setColor(BAD);
                        g.setStroke(new BasicStroke(2.2f));
                        g.drawLine(x - 4, y - 4, x + 4, y + 4);
                        g.drawLine(x - 4, y + 4, x + 4, y - 4);
                        g.setStroke(new BasicStroke(1f));
                    }
                    case "V" -> {
                        g.setColor(AXIS);
                        g.setStroke(new BasicStroke(2f));
                        g.drawLine(x - 4, y, x + 4, y);
                        g.setStroke(new BasicStroke(1f));
                    }
                    default -> {
                        g.setColor(Color.WHITE);
                        g.fillOval(x - 5, y - 5, 10, 10);
                        g.setColor(MUTED);
                        g.setStroke(new BasicStroke(1.5f));
                        g.drawOval(x - 5, y - 5, 10, 10);
                        g.setStroke(new BasicStroke(1f));
                    }
                }
            }
        }

        // ---------------------------------------------------------------- Predictions tab

        static String outcomeText(Pred p) {
            return switch (p.outcome()) {
                case "TRUE" -> "Came true";
                case "FALSE" -> "Didn't happen";
                case "VOID" -> "Void";
                default -> "Pending";
            };
        }

        /** What a prediction claims, without its date (the table has a Due column). */
        static String claim(Pred p) {
            return switch (p.kind()) {
                case "TOP5" -> "Still in the top 5";
                case "SHARE_UP" -> "Bigger share than "
                        + String.format(Locale.ROOT, "%.0f%%", Double.parseDouble(p.param()) * 100);
                case "TONE_PERSIST" -> "Coverage still " + (p.param().equals("NEG") ? "negative" : "positive");
                default -> p.kind();
            };
        }

        /**
         * Every prediction Cassandra has made, in a sortable, filterable, read-only table. It is built once
         * and given fresh data on each reload, so filters, sorting and scroll position survive a scheduled run.
         */
        @SuppressWarnings("serial")
        static final class Ledger extends JPanel {
            static final String[] COLS = {"Made", "Due", "Story", "Prediction", "Type", "Forecast", "Before recal.",
                    "Outcome", "Graded"};
            static final List<String> OUTCOMES = List.of("Pending", "Came true", "Didn't happen", "Void");
            static final DateTimeFormatter D = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK);

            final JFrame owner;
            List<Pred> rows = new ArrayList<>();
            final List<String> typeKeys = new ArrayList<>();   // parallel to the type box, after "All types"
            final javax.swing.table.AbstractTableModel model = new javax.swing.table.AbstractTableModel() {
                @Override public int getRowCount() { return rows.size(); }
                @Override public int getColumnCount() { return COLS.length; }
                @Override public String getColumnName(int c) { return COLS[c]; }
                @Override public Class<?> getColumnClass(int c) {
                    return switch (c) {
                        case 0, 1, 8 -> LocalDate.class;
                        case 5, 6 -> Double.class;
                        default -> String.class;
                    };
                }
                @Override public Object getValueAt(int r, int c) {
                    Pred p = rows.get(r);
                    return switch (c) {
                        case 0 -> p.made();
                        case 1 -> p.due();
                        case 2 -> p.label();
                        case 3 -> claim(p);
                        case 4 -> p.key();
                        case 5 -> p.p();
                        case 6 -> p.pRaw();
                        case 7 -> outcomeText(p);
                        default -> p.graded() || p.outcome().equals("VOID") ? resolvedOn(p) : null;
                    };
                }
            };
            final JTable table = new JTable(model) {
                @Override public String getToolTipText(java.awt.event.MouseEvent e) {
                    int r = rowAtPoint(e.getPoint());
                    return r < 0 ? null : statement(rows.get(convertRowIndexToModel(r)));
                }
            };
            final javax.swing.table.TableRowSorter<javax.swing.table.TableModel> sorter =
                    new javax.swing.table.TableRowSorter<>(model);
            final JComboBox<String> status = new JComboBox<>();
            final JComboBox<String> type = new JComboBox<>();
            final JTextField search = new JTextField(18);
            final JTextArea summary = wrapped(" ", INK);
            final java.awt.CardLayout cards = new java.awt.CardLayout();
            final JPanel body = new JPanel(cards);

            Ledger(JFrame owner) {
                this.owner = owner;
                setLayout(new BorderLayout(0, 10));
                setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

                status.addItem("All outcomes");
                OUTCOMES.forEach(status::addItem);
                type.addItem("All types");
                JButton export = new JButton("Export CSV…");
                export.addActionListener(e -> chooseAndExport());
                JButton clear = new JButton("Clear filters");
                clear.addActionListener(e -> {
                    status.setSelectedIndex(0);
                    type.setSelectedIndex(0);
                    search.setText("");
                });

                JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
                filters.add(new JLabel("Show:"));
                filters.add(status);
                filters.add(type);
                filters.add(new JLabel("Search:"));
                search.setToolTipText("Matches the story, the prediction or its type");
                filters.add(search);
                filters.add(clear);
                JPanel bar = new JPanel(new BorderLayout());
                bar.add(filters, BorderLayout.WEST);
                bar.add(export, BorderLayout.EAST);

                JPanel head = new JPanel(new BorderLayout(0, 8));
                head.add(bar, BorderLayout.NORTH);
                summary.setFont(summary.getFont().deriveFont(13f));
                head.add(summary, BorderLayout.CENTER);
                add(head, BorderLayout.NORTH);

                // table
                table.setRowSorter(sorter);
                table.setRowHeight(24);
                table.setShowVerticalLines(false);
                table.setGridColor(GRID);
                table.setAutoCreateRowSorter(false);
                table.getTableHeader().setReorderingAllowed(false);
                int[] widths = {92, 92, 140, 230, 120, 70, 90, 100, 92};
                for (int c = 0; c < widths.length; c++) table.getColumnModel().getColumn(c).setPreferredWidth(widths[c]);
                sorter.setComparator(7, Comparator.comparingInt((String s) -> OUTCOMES.indexOf(s)));
                sorter.setSortKeys(List.of(new RowSorter.SortKey(0, SortOrder.DESCENDING),
                        new RowSorter.SortKey(2, SortOrder.ASCENDING)));
                javax.swing.table.DefaultTableCellRenderer cell = new javax.swing.table.DefaultTableCellRenderer() {
                    @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel,
                                                                             boolean focus, int r, int c) {
                        Object shown = v instanceof LocalDate d ? d.format(D)
                                : v instanceof Double x ? String.format(Locale.ROOT, "%.0f%%", x * 100)
                                : v == null ? "" : v;
                        Component comp = super.getTableCellRendererComponent(t, shown, sel, focus, r, c);
                        setHorizontalAlignment(v instanceof Double ? SwingConstants.RIGHT : SwingConstants.LEFT);
                        setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 10));
                        if (!sel) {
                            Pred p = rows.get(t.convertRowIndexToModel(r));
                            comp.setForeground(c == 7 ? (p.outcome().equals("TRUE") ? GOOD
                                    : p.outcome().equals("FALSE") ? BAD : MUTED)
                                    : c == 6 && Math.abs(p.p() - p.pRaw()) < 1e-9 ? MUTED : INK);
                        }
                        return comp;
                    }
                };
                for (int c = 0; c < COLS.length; c++) table.getColumnModel().getColumn(c).setCellRenderer(cell);

                JScrollPane sp = new JScrollPane(table);
                sp.getViewport().setBackground(Color.WHITE);
                body.add(sp, "table");
                body.add(message("No predictions in this folder yet.",
                        "They appear here after Cassandra's first run."), "empty");
                add(body, BorderLayout.CENTER);
                add(wrapped("Hover a row for the prediction in full. Click a column heading to sort; shift-click "
                        + "to sort by a second column. \"Before recal.\" is grey where recalibration made no change. "
                        + "Export saves the rows currently shown, in the order shown.", MUTED), BorderLayout.SOUTH);

                status.addActionListener(e -> refilter());
                type.addActionListener(e -> refilter());
                search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                    @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { refilter(); }
                    @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { refilter(); }
                    @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { refilter(); }
                });
            }

            /** New data from a reload. Filters, sort order and the chosen type are kept. */
            void setData(Collection<Pred> preds) {
                rows = new ArrayList<>(preds);
                String keepType = type.getSelectedIndex() > 0 ? typeKeys.get(type.getSelectedIndex() - 1) : null;
                List<String> keys = rows.stream().map(Pred::key).distinct().sorted().toList();
                if (!keys.equals(typeKeys)) {
                    typeKeys.clear();
                    typeKeys.addAll(keys);
                    type.removeAllItems();
                    type.addItem("All types");
                    for (String k : keys) type.addItem(typeLabel(k));
                    type.setSelectedIndex(keepType == null || !keys.contains(keepType) ? 0 : keys.indexOf(keepType) + 1);
                }
                model.fireTableDataChanged();
                cards.show(body, rows.isEmpty() ? "empty" : "table");
                refilter();
            }

            void refilter() {
                int st = status.getSelectedIndex();
                String wanted = st <= 0 ? null : OUTCOMES.get(st - 1);
                int ti = type.getSelectedIndex();
                String key = ti <= 0 || ti - 1 >= typeKeys.size() ? null : typeKeys.get(ti - 1);
                String q = search.getText().trim().toLowerCase(Locale.ROOT);
                sorter.setRowFilter(new RowFilter<>() {
                    @Override public boolean include(Entry<? extends javax.swing.table.TableModel, ? extends Integer> e) {
                        Pred p = rows.get(e.getIdentifier());
                        if (wanted != null && !outcomeText(p).equals(wanted)) return false;
                        if (key != null && !p.key().equals(key)) return false;
                        return q.isEmpty() || (p.label() + " " + p.term() + " " + claim(p) + " " + typeLabel(p.key())
                                + " " + p.key()).toLowerCase(Locale.ROOT).contains(q);
                    }
                });
                summary.setText(summarise(shown()));
            }

            /** The rows currently visible, in the order shown. */
            List<Pred> shown() {
                List<Pred> out = new ArrayList<>();
                for (int i = 0; i < table.getRowCount(); i++) out.add(rows.get(table.convertRowIndexToModel(i)));
                return out;
            }

            String summarise(List<Pred> ps) {
                if (rows.isEmpty()) return " ";
                List<Pred> graded = ps.stream().filter(Pred::graded).toList();
                String s = String.format(Locale.ROOT, "Showing %d of %d predictions.", ps.size(), rows.size());
                if (graded.isEmpty()) return s + " None of these has been graded yet.";
                long hits = graded.stream().filter(p -> p.outcome().equals("TRUE")).count();
                return s + String.format(Locale.ROOT, " Of the %d graded: %d came true (%.0f%%), against an average "
                        + "forecast of %.0f%%. Brier score %.3f (a coin flip scores 0.250).", graded.size(), hits,
                        100.0 * hits / graded.size(), 100 * graded.stream().mapToDouble(Pred::p).average().orElse(0),
                        brier(graded, Pred::p));
            }

            void chooseAndExport() {
                JFileChooser fc = new JFileChooser();
                fc.setSelectedFile(new java.io.File(System.getProperty("user.home"), "cassandra-predictions.csv"));
                if (fc.showSaveDialog(owner) != JFileChooser.APPROVE_OPTION) return;
                Path target = fc.getSelectedFile().toPath();
                if (Files.exists(target) && JOptionPane.showConfirmDialog(owner, target.getFileName()
                        + " already exists. Replace it?", "Export CSV", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION)
                    return;
                try {
                    int n = exportCsv(target, shown());
                    JOptionPane.showMessageDialog(owner, "Saved " + n + " predictions to\n" + target);
                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(owner, "Couldn't save " + target + "\n" + ex.getMessage(),
                            "Export CSV", JOptionPane.ERROR_MESSAGE);
                }
            }

            /** Write predictions as CSV (RFC 4180 quoting). Returns how many were written. */
            static int exportCsv(Path target, List<Pred> ps) throws IOException {
                List<String> lines = new ArrayList<>();
                lines.add("id,made,due,story,term,type,claim,statement,probability,probability_before_recalibration,"
                        + "outcome,graded_on");
                for (Pred p : ps)
                    lines.add(String.join(",", csv(p.id()), p.made().toString(), p.due().toString(), csv(p.label()),
                            csv(p.term()), csv(p.key()), csv(claim(p)), csv(statement(p)),
                            String.format(Locale.ROOT, "%.4f", p.p()), String.format(Locale.ROOT, "%.4f", p.pRaw()),
                            csv(outcomeText(p)), p.resolved().equals("-") ? "" : p.resolved()));
                Files.write(target, lines, StandardCharsets.UTF_8);
                return ps.size();
            }

            static String csv(String s) {
                return s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
            }
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

        static void open(Path dir, boolean demo) {
            if (GraphicsEnvironment.isHeadless()) {
                System.out.println("--gui needs a display. Run it on your desktop, or use --report in a terminal.");
                return;
            }
            System.setProperty("apple.laf.useScreenMenuBar", "true"); // on a Mac, menus go in the top menu bar
            System.setProperty("apple.awt.application.name", "Cassandra");
            SwingUtilities.invokeLater(() -> {
                try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
                catch (Exception ignored) { /* keep the default look */ }
                new Gui(dir, demo).frame.setVisible(true);
            });
        }

        static JComponent section(String title, JComponent body) {
            JPanel p = new JPanel(new BorderLayout(0, 8));
            p.setBackground(Color.WHITE);
            p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(GRID),
                    BorderFactory.createEmptyBorder(12, 16, 12, 16)));
            JLabel h = new JLabel(title);
            h.setFont(h.getFont().deriveFont(Font.BOLD, 14f));
            h.setForeground(INK);
            p.add(h, BorderLayout.NORTH);
            transparent(body);
            p.add(body, BorderLayout.CENTER);
            return p;
        }

        /** Let a card's white show through its inner layout panels. */
        static void transparent(JComponent c) {
            if (c instanceof JPanel || c instanceof JRadioButton) c.setOpaque(false);
            for (Component k : c.getComponents()) if (k instanceof JComponent j && !(k instanceof JScrollPane)) transparent(j);
        }

        // ---------------------------------------------------------------- Run tab

        /** Sends text printed during a run, line by line, to a consumer (the Run tab's log). */
        static final class LineSink extends java.io.OutputStream {
            final java.util.function.Consumer<String> to;
            final java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();

            LineSink(java.util.function.Consumer<String> to) { this.to = to; }

            @Override public synchronized void write(int b) {
                if (b == '\n') flushLine(); else buf.write(b);
            }

            synchronized void flushLine() {
                to.accept(buf.toString(StandardCharsets.UTF_8));
                buf.reset();
            }
        }

        /**
         * Run Cassandra from the window: today's run on live feeds, or demo days, plus a health check of the
         * folder's run history. Runs happen in the background and print to the log here.
         */
        @SuppressWarnings("serial")
        static final class RunPanel extends JPanel {
            final Gui gui;
            final JRadioButton live = new JRadioButton("Live news");
            final JRadioButton demo = new JRadioButton("Demo headlines (offline)");
            final JTextArea modeNote = wrapped(" ", MUTED);
            final JButton runNow = new JButton("Run now");
            final JButton simulate = new JButton("Simulate");
            final JSpinner days = new JSpinner(new SpinnerNumberModel(35, 1, 120, 1));
            final JLabel daysLabel = new JLabel("days");
            final JPanel simGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));   // demo mode only
            final JButton stop = new JButton("Stop");
            final JProgressBar progress = new JProgressBar();
            final JTextArea health = wrapped(" ", INK);
            final JTextArea log = new JTextArea();
            volatile boolean stopRequested;
            boolean running;
            Path dir;
            List<HistRow> history = List.of();

            RunPanel(Gui gui) {
                this.gui = gui;
                setLayout(new BorderLayout(0, 12));
                setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

                ButtonGroup g = new ButtonGroup();
                g.add(live);
                g.add(demo);
                live.addActionListener(e -> gui.setDemoMode(false));
                demo.addActionListener(e -> gui.setDemoMode(true));
                JPanel modes = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
                modes.add(new JLabel("Headlines from:"));
                modes.add(live);
                modes.add(demo);

                runNow.addActionListener(e -> start(gui.demoMode ? List.of(nextDemoDay())
                        : List.of(LocalDate.now(ZoneOffset.UTC)), true));
                simulate.addActionListener(e -> {
                    List<LocalDate> ds = new ArrayList<>();
                    LocalDate d = nextDemoDay();
                    for (int i = 0; i < (Integer) days.getValue(); i++) ds.add(d.plusDays(i));
                    start(ds, false);
                });
                stop.addActionListener(e -> {
                    stopRequested = true;
                    stop.setEnabled(false);
                    append("Stopping after the current day…");
                });
                stop.setEnabled(false);
                progress.setStringPainted(true);
                progress.setString("Idle");
                JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
                buttons.add(runNow);
                simGroup.add(simulate);
                simGroup.add(days);
                simGroup.add(daysLabel);
                buttons.add(simGroup);
                buttons.add(stop);
                modes.setOpaque(false);
                buttons.setOpaque(false);
                live.setOpaque(false);
                demo.setOpaque(false);
                JPanel controls = new JPanel(new BorderLayout(0, 8));
                controls.add(modes, BorderLayout.NORTH);
                controls.add(modeNote, BorderLayout.CENTER);
                JPanel row = new JPanel(new BorderLayout(0, 8));
                row.setOpaque(false);
                row.add(buttons, BorderLayout.NORTH);
                row.add(progress, BorderLayout.SOUTH);
                controls.add(row, BorderLayout.SOUTH);

                JPanel top = new JPanel(new GridLayout(1, 2, 12, 0));
                top.add(section("Run Cassandra", controls));
                top.add(section("Run health", health));
                add(top, BorderLayout.NORTH);

                log.setEditable(false);
                log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
                log.setMargin(new Insets(8, 10, 8, 10));
                ((javax.swing.text.DefaultCaret) log.getCaret()).setUpdatePolicy(javax.swing.text.DefaultCaret.ALWAYS_UPDATE);
                JScrollPane sp = new JScrollPane(log);
                JButton clear = new JButton("Clear log");
                clear.addActionListener(e -> log.setText(""));
                JPanel logHead = new JPanel(new BorderLayout());
                JLabel lh = new JLabel("Log");
                lh.setFont(lh.getFont().deriveFont(Font.BOLD, 14f));
                logHead.add(lh, BorderLayout.WEST);
                logHead.add(clear, BorderLayout.EAST);
                JPanel logPanel = new JPanel(new BorderLayout(0, 6));
                logPanel.add(logHead, BorderLayout.NORTH);
                logPanel.add(sp, BorderLayout.CENTER);
                add(logPanel, BorderLayout.CENTER);
                updateMode();
            }

            void updateMode() {
                live.setSelected(!gui.demoMode);
                demo.setSelected(gui.demoMode);
                boolean d = gui.demoMode;
                runNow.setText(d ? "Run next demo day" : "Run now");
                simGroup.setVisible(d);
                modeNote.setText(d
                        ? "Demo mode uses synthetic headlines and its own folder ("
                        + gui.demoDir.toAbsolutePath().normalize() + "), so it never touches your real track record. "
                        + "Each run is the next day of the demo storyline."
                        : "Fetches today's headlines from your feeds (see Settings) and runs exactly as a scheduled run "
                        + "would, saving to " + gui.liveDir.toAbsolutePath().normalize() + ". Running again on the same "
                        + "day replaces that day's history; predictions already made today are kept.");
            }

            /** The day after the demo folder's latest run, or the start of the demo storyline. */
            LocalDate nextDemoDay() {
                return history.stream().map(HistRow::date).max(Comparator.naturalOrder())
                        .map(d -> d.plusDays(1)).orElse(DEMO_START);
            }

            void setData(Path dir, List<HistRow> history, Collection<Pred> preds) {
                this.dir = dir;
                this.history = history;
                updateMode();
                health.setText(healthText(history, preds));
            }

            String healthText(List<HistRow> history, Collection<Pred> preds) {
                if (history.isEmpty())
                    return "No runs recorded in this folder yet." + (gui.demoMode
                            ? " Run next demo day starts the storyline on " + DEMO_START.format(LONG_DAY) + "."
                            : " Press Run now, or set up a scheduled job (see the README).");
                TreeSet<LocalDate> runs = new TreeSet<>();
                for (HistRow r : history) runs.add(r.date());
                LocalDate last = runs.last();
                long voided = preds.stream().filter(p -> p.outcome().equals("VOID")).count();
                long settled = preds.stream().filter(p -> !p.outcome().equals("PENDING")).count();
                StringBuilder s = new StringBuilder();
                if (gui.demoMode) {
                    s.append("Latest demo day: ").append(last.format(LONG_DAY)).append(". Next run: ")
                            .append(last.plusDays(1).format(LONG_DAY)).append(".");
                } else {
                    LocalDate today = LocalDate.now(ZoneOffset.UTC);
                    long age = ChronoUnit.DAYS.between(last, today);
                    s.append("Latest run: ").append(last.format(LONG_DAY)).append(age == 0 ? " (today)."
                            : age == 1 ? " (yesterday)." : age > 1 ? " (" + age + " days ago)." : ".");
                    // missed days: from the first run to yesterday (today may not have run yet), last 30 days
                    LocalDate end = runs.contains(today) ? today : today.minusDays(1);
                    LocalDate start = runs.first().isAfter(end.minusDays(29)) ? runs.first() : end.minusDays(29);
                    List<LocalDate> missed = new ArrayList<>();
                    for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) if (!runs.contains(d)) missed.add(d);
                    if (missed.isEmpty())
                        s.append(" No missed days in the last 30.");
                    else {
                        s.append(" Missed ").append(missed.size()).append(missed.size() == 1 ? " day" : " days")
                                .append(" in the last 30: ");
                        s.append(String.join(", ", missed.stream().limit(10).map(d -> d.format(DAY)).toList()));
                        if (missed.size() > 10) s.append(" and ").append(missed.size() - 10).append(" more");
                        s.append(". Check your scheduled job.");
                    }
                }
                s.append(String.format(Locale.ROOT, "%n%nRuns recorded: %d, from %s.", runs.size(), runs.first().format(DAY)));
                int archived = archiveFiles(dir).size();
                s.append(archived == 0 ? " No archived headlines yet (runs archive them from version 2.0 on)."
                        : " Days of headlines archived for replay: " + archived + ".");
                if (settled > 0)
                    s.append(String.format(Locale.ROOT, " Predictions voided because no run graded them within %d "
                            + "days of falling due: %d of %d settled.", GRACE_DAYS, voided, settled));
                return s.toString();
            }

            void append(String line) {
                log.append(line + "\n");
            }

            void setRunning(boolean on) {
                lockControls(on);
                stop.setEnabled(on);
                gui.settingsPanel.setRunning(on);
            }

            /** Disable everything that starts a run or changes folder (also used while a replay test runs). */
            void lockControls(boolean on) {
                running = on;
                runNow.setEnabled(!on);
                simulate.setEnabled(!on);
                days.setEnabled(!on);
                live.setEnabled(!on);
                demo.setEnabled(!on);
                gui.choose.setEnabled(!on);
            }

            /**
             * Run one day per date, in the background. verbose prints the full report (as on the command line);
             * otherwise each day gets a one-line summary.
             */
            void start(List<LocalDate> dates, boolean verbose) {
                if (running) return;
                boolean isDemo = gui.demoMode;
                Path target = gui.dir;
                stopRequested = false;
                setRunning(true);
                progress.setIndeterminate(dates.size() == 1);
                progress.setMaximum(dates.size());
                progress.setValue(0);
                progress.setString(dates.size() == 1 ? "Running…" : "Day 0 of " + dates.size());
                append((isDemo ? "=== Demo " : "=== Live ") + (dates.size() == 1 ? "run for " + dates.get(0).format(LONG_DAY)
                        : dates.size() + " days from " + dates.get(0).format(DAY)) + ", saving to "
                        + target.toAbsolutePath().normalize() + " ===");

                new SwingWorker<Integer, String>() {
                    @Override protected Integer doInBackground() {
                        java.io.PrintStream original = System.out;
                        java.io.PrintStream tee = new java.io.PrintStream(new LineSink(this::publish), true,
                                StandardCharsets.UTF_8);
                        System.setOut(tee);
                        int done = 0;
                        try {
                            loadSettings(target).apply();
                            for (LocalDate d : dates) {
                                if (stopRequested) break;
                                TODAY = d;
                                NOW = isDemo ? d.atTime(12, 0).toInstant(ZoneOffset.UTC) : Instant.now();
                                FORMS.clear();   // as in a fresh run: labels come from this run's headlines only
                                List<Article> articles = isDemo ? demoArticles()
                                        : fetchAll(loadSettings(target).feeds());
                                articles = dedupe(articles);
                                if (articles.size() < MIN_ARTICLES) {
                                    System.out.println(d.format(DAY) + ": only " + articles.size() + " headlines, "
                                            + "not enough to run (needs " + MIN_ARTICLES + "). Nothing saved.");
                                } else if (verbose) {
                                    report(articles, target, true);
                                } else {
                                    RunResult r = forecastRun(articles, target, true);
                                    System.out.println(r == null ? d.format(DAY) + ": no story had enough coverage."
                                            : String.format(Locale.ROOT, "%s: %d headlines, top story \"%s\"; %d new "
                                            + "predictions, %d graded today.", d.format(DAY), r.headlines(),
                                            r.lead().label(), r.added(), r.gradedNow().size()));
                                }
                                done++;
                                setProgress(Math.min(100, 100 * done / dates.size()));
                                publish("\u0000" + done);   // progress marker for the bar
                            }
                        } catch (BusyException e) {
                            System.out.println(e.getMessage());
                        } catch (Exception e) {
                            System.out.println("The run failed: " + e);
                        } finally {
                            tee.flush();
                            System.setOut(original);
                            TODAY = LocalDate.now(ZoneOffset.UTC);
                            NOW = Instant.now();
                        }
                        return done;
                    }

                    @Override protected void process(List<String> lines) {
                        for (String l : lines) {
                            if (l.startsWith("\u0000")) {
                                int n = Integer.parseInt(l.substring(1));
                                progress.setValue(n);
                                if (dates.size() > 1) progress.setString("Day " + n + " of " + dates.size());
                            } else append(l);
                        }
                    }

                    @Override protected void done() {
                        int n;
                        try { n = get(); } catch (Exception e) { n = 0; }
                        progress.setIndeterminate(false);
                        progress.setString(n == dates.size() ? "Finished" : "Stopped after " + n + " of " + dates.size());
                        append("=== " + (n == dates.size() ? "Finished" : "Stopped") + " ===\n");
                        setRunning(false);
                        gui.reload();
                    }
                }.execute();
            }
        }

        // ---------------------------------------------------------------- Settings tab

        /**
         * Edit the data folder's cassandra.properties: the feeds and the thresholds. Unsaved edits survive the
         * automatic reload; saving takes effect from the next run, scheduled ones included.
         */
        @SuppressWarnings("serial")
        static final class SettingsPanel extends JPanel {
            final Gui gui;
            final DefaultListModel<String> feeds = new DefaultListModel<>();
            final JList<String> feedList = new JList<>(feeds);
            final JTextField newFeed = new JTextField(32);
            final JSpinner minArticles = new JSpinner(new SpinnerNumberModel(8, 3, 50, 1));
            final JSpinner predicted = new JSpinner(new SpinnerNumberModel(3, 1, 5, 1));
            final JSpinner rankPenalty = new JSpinner(new SpinnerNumberModel(0.07, 0.0, 0.2, 0.01));
            final JSpinner historyDays = new JSpinner(new SpinnerNumberModel(14, 3, 60, 1));
            final JSpinner calibration = new JSpinner(new SpinnerNumberModel(5, 2, 50, 1));
            final JSpinner grace = new JSpinner(new SpinnerNumberModel(2, 0, 7, 1));
            final JButton save = new JButton("Save");
            final JButton revert = new JButton("Revert");
            final JButton testArchive = new JButton("Test on archive…");
            final JLabel state = new JLabel(" ");
            final JTextArea where = wrapped(" ", MUTED);
            Path dir;
            boolean dirty, loading, running;

            SettingsPanel(Gui gui) {
                this.gui = gui;
                setLayout(new BorderLayout(0, 12));
                setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

                // top: where the settings live and what changing them means
                JPanel head = new JPanel(new BorderLayout(0, 6));
                JLabel h = new JLabel("Settings for this data folder");
                h.setFont(h.getFont().deriveFont(Font.BOLD, 18f));
                h.setForeground(INK);
                head.add(h, BorderLayout.NORTH);
                head.add(where, BorderLayout.CENTER);
                JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
                actions.add(save);
                actions.add(revert);
                JButton defaults = new JButton("Restore all defaults");
                actions.add(defaults);
                testArchive.setToolTipText("Replay this folder's archived headlines with the saved settings and with "
                        + "your edits, and compare how well each would have predicted");
                testArchive.addActionListener(e -> testOnArchive());
                actions.add(Box.createHorizontalStrut(8));
                actions.add(testArchive);
                actions.add(Box.createHorizontalStrut(8));
                state.setForeground(MUTED);
                actions.add(state);
                head.add(actions, BorderLayout.SOUTH);
                add(head, BorderLayout.NORTH);

                // left: feeds
                feedList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
                feedList.setVisibleRowCount(10);
                JButton add = new JButton("Add");
                JButton remove = new JButton("Remove");
                JButton test = new JButton("Test selected");
                JButton defaultFeeds = new JButton("Restore default feeds");
                add.addActionListener(e -> addFeed());
                newFeed.addActionListener(e -> addFeed());
                remove.addActionListener(e -> {
                    int[] sel = feedList.getSelectedIndices();
                    for (int i = sel.length - 1; i >= 0; i--) feeds.remove(sel[i]);
                });
                test.addActionListener(e -> testFeeds());
                defaultFeeds.addActionListener(e -> {
                    feeds.clear();
                    DEFAULT_FEEDS.forEach(feeds::addElement);
                });
                JPanel addRow = new JPanel(new BorderLayout(6, 0));
                addRow.setOpaque(false);
                addRow.add(newFeed, BorderLayout.CENTER);
                addRow.add(add, BorderLayout.EAST);
                JPanel feedButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
                feedButtons.setOpaque(false);
                feedButtons.add(remove);
                feedButtons.add(test);
                feedButtons.add(defaultFeeds);
                JPanel feedBody = new JPanel(new BorderLayout(0, 8));
                feedBody.add(wrapped("RSS 2.0 feeds Cassandra reads on live runs. Paste a feed address and press "
                        + "Add. Feeds given on the command line take priority over this list.", MUTED), BorderLayout.NORTH);
                feedBody.add(new JScrollPane(feedList), BorderLayout.CENTER);
                JPanel feedSouth = new JPanel(new BorderLayout(0, 8));
                feedSouth.setOpaque(false);
                feedSouth.add(addRow, BorderLayout.NORTH);
                feedSouth.add(feedButtons, BorderLayout.SOUTH);
                feedBody.add(feedSouth, BorderLayout.SOUTH);

                // right: thresholds
                JPanel th = new JPanel(new java.awt.GridBagLayout());
                java.awt.GridBagConstraints g = new java.awt.GridBagConstraints();
                g.anchor = java.awt.GridBagConstraints.NORTHWEST;
                g.insets = new Insets(6, 0, 6, 10);
                Object[][] rows = {
                        {"Headlines needed for a run", minArticles,
                                "A run with fewer headlines than this stops without saving anything."},
                        {"Stories that get predictions", predicted,
                                "How many of the day's leading stories get predictions (1 to 5)."},
                        {"Rank penalty", rankPenalty,
                                "How much lower the starting \"still in the top 5\" probability is for each rank below first."},
                        {"Trend look-back (days)", historyDays, "How many days of history the coverage trend is fitted over."},
                        {"Results before recalibrating", calibration,
                                "Graded results a prediction type needs before its probabilities are corrected."},
                        {"Grace period (days)", grace,
                                "How long after its due date a prediction can still be graded. After that it is voided."}};
                for (int i = 0; i < rows.length; i++) {
                    g.gridy = i;
                    g.gridx = 0;
                    g.weightx = 0;
                    g.fill = java.awt.GridBagConstraints.NONE;
                    JLabel l = new JLabel((String) rows[i][0]);
                    l.setForeground(INK);
                    th.add(l, g);
                    g.gridx = 1;
                    JSpinner sp = (JSpinner) rows[i][1];
                    sp.setPreferredSize(new Dimension(72, sp.getPreferredSize().height));
                    th.add(sp, g);
                    g.gridx = 2;
                    g.weightx = 1;
                    g.fill = java.awt.GridBagConstraints.HORIZONTAL;
                    th.add(wrapped((String) rows[i][2], MUTED), g);
                }
                JPanel thBody = new JPanel(new BorderLayout(0, 8));
                thBody.add(th, BorderLayout.NORTH);
                thBody.add(wrapped("Changes apply from the next run. Past predictions are never re-scored, so changing "
                        + "these partway through means the track record mixes two set-ups.", MUTED), BorderLayout.SOUTH);

                JPanel cols = new JPanel(new GridLayout(1, 2, 12, 0));
                cols.add(section("News feeds", feedBody));
                cols.add(section("Thresholds", thBody));
                JPanel holder = new JPanel(new BorderLayout());
                holder.add(cols, BorderLayout.NORTH);
                add(holder, BorderLayout.CENTER);

                // change tracking
                feeds.addListDataListener(new javax.swing.event.ListDataListener() {
                    @Override public void intervalAdded(javax.swing.event.ListDataEvent e) { changed(); }
                    @Override public void intervalRemoved(javax.swing.event.ListDataEvent e) { changed(); }
                    @Override public void contentsChanged(javax.swing.event.ListDataEvent e) { changed(); }
                });
                for (JSpinner sp : List.of(minArticles, predicted, rankPenalty, historyDays, calibration, grace)) {
                    sp.addChangeListener(e -> changed());
                    if (sp == rankPenalty) sp.setEditor(new JSpinner.NumberEditor(sp, "0.00"));
                }
                save.addActionListener(e -> save());
                revert.addActionListener(e -> {
                    dirty = false;
                    fill(loadSettings(dir));
                });
                defaults.addActionListener(e -> fill(Settings.defaults(), true));
                updateState(null);
            }

            void changed() {
                if (loading) return;
                dirty = true;
                updateState(null);
            }

            void updateState(String message) {
                save.setEnabled(dirty && !running);
                revert.setEnabled(dirty);
                testArchive.setEnabled(!running && dir != null && !archiveFiles(dir).isEmpty());
                state.setText(message != null ? message : dirty ? "Unsaved changes" : " ");
                state.setForeground(dirty && message == null ? new Color(0x8a5a00) : MUTED);
            }

            void setRunning(boolean on) {
                running = on;
                updateState(null);
            }

            /** The thresholds that differ between two settings, in words ("Stories that get predictions: 3 → 2"). */
            static List<String> differences(Settings a, Settings b) {
                List<String> out = new ArrayList<>();
                if (a.minArticles() != b.minArticles())
                    out.add("Headlines needed for a run: " + a.minArticles() + " → " + b.minArticles());
                if (a.predictedStories() != b.predictedStories())
                    out.add("Stories that get predictions: " + a.predictedStories() + " → " + b.predictedStories());
                if (Math.abs(a.rankPenalty() - b.rankPenalty()) > 1e-9)
                    out.add("Rank penalty: " + plain(a.rankPenalty()) + " → " + plain(b.rankPenalty()));
                if (a.historyDays() != b.historyDays())
                    out.add("Trend look-back: " + a.historyDays() + " → " + b.historyDays() + " days");
                if (a.minForCalibration() != b.minForCalibration())
                    out.add("Results before recalibrating: " + a.minForCalibration() + " → " + b.minForCalibration());
                if (a.graceDays() != b.graceDays())
                    out.add("Grace period: " + a.graceDays() + " → " + b.graceDays() + " days");
                return out;
            }

            /**
             * Replay the folder's archive twice, in temporary folders, once with the saved settings and once with the
             * edits in the form, and show how each would have done. Nothing in the data folder changes.
             */
            void testOnArchive() {
                Settings saved = loadSettings(dir);
                Settings edited = current();
                List<String> diff = differences(saved, edited);
                if (diff.isEmpty()) {
                    JOptionPane.showMessageDialog(gui.frame, "Change one or more thresholds first. This test replays the "
                            + "archive with the saved settings and with your edits, and compares the two.\n(Feeds "
                            + "don't matter here: a replay uses the archived headlines.)", "Test on archive",
                            JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
                int days = archiveFiles(dir).size();
                Path source = dir;

                JDialog wait = new JDialog(gui.frame, "Test on archive", true);
                JProgressBar bar = new JProgressBar(0, 2 * days);
                bar.setStringPainted(true);
                JButton cancel = new JButton("Cancel");
                boolean[] stop = new boolean[1];
                cancel.addActionListener(e -> { stop[0] = true; cancel.setEnabled(false); });
                JPanel wp = new JPanel(new BorderLayout(0, 10));
                wp.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
                wp.add(new JLabel("Replaying " + days + " archived days twice…"), BorderLayout.NORTH);
                bar.setPreferredSize(new Dimension(380, bar.getPreferredSize().height));
                wp.add(bar, BorderLayout.CENTER);
                JPanel cp = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
                cp.add(cancel);
                wp.add(cp, BorderLayout.SOUTH);
                wait.setContentPane(wp);
                wait.pack();
                wait.setLocationRelativeTo(gui.frame);
                wait.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

                gui.runPanel.lockControls(true);
                setRunning(true);
                new SwingWorker<Accuracy[], String>() {
                    int done;

                    @Override protected Accuracy[] doInBackground() throws Exception {
                        Path a = Files.createTempDirectory("cassandra-saved-");
                        Path b = Files.createTempDirectory("cassandra-edited-");
                        java.io.PrintStream original = System.out;
                        System.setOut(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
                        try {
                            publish("Saved settings");
                            replay(source, a, saved, line -> publish(""), () -> stop[0]);
                            publish("Your edits");
                            replay(source, b, edited, line -> publish(""), () -> stop[0]);
                            if (stop[0]) return null;
                            return new Accuracy[]{loadAccuracy(a), loadAccuracy(b)};
                        } finally {
                            System.setOut(original);
                            saved.apply();   // a replay applies its settings; put the saved ones back
                            deleteTree(a);
                            deleteTree(b);
                        }
                    }

                    @Override protected void process(List<String> chunks) {
                        for (String c : chunks) {
                            if (c.isEmpty()) bar.setValue(++done);
                            else bar.setString(c + "…");
                        }
                    }

                    @Override protected void done() {
                        wait.dispose();
                        gui.runPanel.lockControls(false);
                        setRunning(false);
                        Accuracy[] r;
                        try { r = get(); } catch (Exception e) {
                            JOptionPane.showMessageDialog(gui.frame, "The test failed: " + e.getMessage(),
                                    "Test on archive", JOptionPane.ERROR_MESSAGE);
                            return;
                        }
                        if (r == null) return;   // cancelled
                        showComparison(days, diff, r[0], r[1]);
                    }
                }.execute();
                wait.setVisible(true);   // modal until the worker disposes it
            }

            static void deleteTree(Path p) {
                try (var s = Files.walk(p)) {
                    s.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
                } catch (IOException ignored) { /* temporary files; the system cleans them up eventually */ }
            }

            void showComparison(int days, List<String> diff, Accuracy a, Accuracy b) {
                String[] rows = {"Predictions graded", "Came true", "Average forecast", "Brier score (lower is better)",
                        "Skill vs base rate", "90% interval for skill"};
                java.util.function.Function<Accuracy, String[]> col = x -> x == null || x.graded().isEmpty()
                        ? new String[]{"0", "–", "–", "–", "–", "–"}
                        : new String[]{String.valueOf(x.graded().size()),
                        pct(x.graded().stream().mapToDouble(Cassandra::outcome).average().orElse(0)),
                        pct(x.graded().stream().mapToDouble(Pred::p).average().orElse(0)),
                        String.format(Locale.ROOT, "%.3f", x.bCass()), num(x.skill(), "%+.2f"),
                        x.ci() == null ? "too few to judge"
                                : String.format(Locale.ROOT, "%+.2f to %+.2f", x.ci()[0], x.ci()[1])};
                String[] ca = col.apply(a), cb = col.apply(b);
                StringBuilder h = new StringBuilder("<html><body style='font-family: sans-serif; width: 470px'>");
                h.append("<p>Both replays used the same <b>").append(days).append(" archived days</b>. Your edits:</p><ul>");
                for (String d : diff) h.append("<li>").append(escape(d)).append("</li>");
                h.append("</ul><table cellpadding='5' cellspacing='0'>")
                        .append("<tr><th></th><th align='right'>Saved settings</th><th align='right'>&nbsp;&nbsp;Your edits</th></tr>");
                for (int i = 0; i < rows.length; i++)
                    h.append("<tr><td>").append(rows[i]).append("</td><td align='right'>").append(ca[i])
                            .append("</td><td align='right'>&nbsp;&nbsp;<b>").append(cb[i]).append("</b></td></tr>");
                h.append("</table><p style='margin-top: 10px'>").append(escape(verdict(a, b))).append("</p>")
                        .append("<p style='margin-top: 8px; color: #6b7280'>The data folder was not changed. Save your edits to use them "
                                + "from the next run, or Revert to discard them.</p></body></html>");
                JOptionPane.showMessageDialog(gui.frame, new JLabel(h.toString()), "Test on archive: results",
                        JOptionPane.PLAIN_MESSAGE);
            }

            /** A plain reading of the comparison, which does not claim more than the intervals support. */
            static String verdict(Accuracy a, Accuracy b) {
                if (a == null || b == null || a.ci() == null || b.ci() == null)
                    return "Too few graded predictions to compare skill fairly yet. A longer archive will give a "
                            + "clearer answer.";
                double d = b.skill() - a.skill();
                boolean overlap = b.ci()[0] <= a.ci()[1] && a.ci()[0] <= b.ci()[1];
                String dir = Math.abs(d) < 0.01 ? "about the same skill as" : d > 0 ? "more skill than" : "less skill than";
                return "Your edits show " + dir + " the saved settings (" + String.format(Locale.ROOT, "%+.2f", d)
                        + " in skill). " + (overlap
                        ? "The two intervals overlap, so the difference could be luck; don't read much into it yet."
                        : "The intervals don't overlap, so the difference is probably real, at least on these days.")
                        + (a.graded().size() != b.graded().size()
                        ? " Note that the two made different numbers of predictions, so they were not tested on "
                        + "exactly the same questions." : "");
            }

            /** Fresh settings from a reload. Unsaved edits to the same folder are kept. */
            void setData(Path folder, Settings s) {
                boolean sameFolder = folder.equals(dir);
                dir = folder;
                Path f = folder.resolve(CONFIG).toAbsolutePath().normalize();
                where.setText((Files.exists(f) ? "Saved in " + f : "Using the defaults. Saving creates " + f)
                        + ". Cassandra reads this file on every run in this folder, scheduled ones included.");
                if (dirty && sameFolder) return;
                dirty = false;
                fill(s);
            }

            void fill(Settings s) { fill(s, false); }

            void fill(Settings s, boolean markDirty) {
                loading = true;
                feeds.clear();
                s.feeds().forEach(feeds::addElement);
                minArticles.setValue(s.minArticles());
                predicted.setValue(s.predictedStories());
                rankPenalty.setValue(s.rankPenalty());
                historyDays.setValue(s.historyDays());
                calibration.setValue(s.minForCalibration());
                grace.setValue(s.graceDays());
                loading = false;
                if (markDirty) dirty = true;
                updateState(null);
            }

            Settings current() {
                return new Settings(Collections.list(feeds.elements()), (Integer) minArticles.getValue(),
                        (Integer) predicted.getValue(), Math.round((Double) rankPenalty.getValue() * 100) / 100.0,
                        (Integer) historyDays.getValue(), (Integer) calibration.getValue(), (Integer) grace.getValue());
            }

            void addFeed() {
                String u = newFeed.getText().trim();
                if (u.isEmpty()) return;
                if (!(u.startsWith("https://") || u.startsWith("http://"))) {
                    JOptionPane.showMessageDialog(gui.frame, "A feed address must start with https:// or http://",
                            "Add feed", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                if (!feeds.contains(u)) feeds.addElement(u);
                newFeed.setText("");
            }

            void testFeeds() {
                List<String> sel = feedList.getSelectedValuesList();
                if (sel.isEmpty()) sel = Collections.list(feeds.elements());
                List<String> urls = sel;
                state.setText("Testing " + urls.size() + (urls.size() == 1 ? " feed…" : " feeds…"));
                new SwingWorker<String, Void>() {
                    @Override protected String doInBackground() {
                        StringBuilder out = new StringBuilder();
                        for (String u : urls) {
                            try {
                                out.append("✓ ").append(fetchFeed(u).size()).append(" headlines: ").append(u);
                            } catch (Exception e) {
                                out.append("✗ Could not read ").append(u).append(" (").append(e.getMessage()).append(")");
                            }
                            out.append('\n');
                        }
                        return out.toString();
                    }
                    @Override protected void done() {
                        updateState(null);
                        String text;
                        try { text = get(); } catch (Exception e) { text = e.toString(); }
                        JTextArea t = new JTextArea(text.trim());
                        t.setEditable(false);
                        JOptionPane.showMessageDialog(gui.frame, new JScrollPane(t), "Feed test",
                                JOptionPane.INFORMATION_MESSAGE);
                    }
                }.execute();
            }

            void save() {
                Settings s = current();
                if (s.feeds().isEmpty()) {
                    JOptionPane.showMessageDialog(gui.frame, "Add at least one feed before saving.", "Settings",
                            JOptionPane.WARNING_MESSAGE);
                    return;
                }
                try {
                    saveSettings(dir, s);
                    dirty = false;
                    updateState("Saved at " + java.time.LocalTime.now().withNano(0) + ". Used from the next run.");
                    setData(dir, s);
                    updateState("Saved at " + java.time.LocalTime.now().withNano(0) + ". Used from the next run.");
                } catch (IOException e) {
                    JOptionPane.showMessageDialog(gui.frame, "Couldn't save the settings: " + e.getMessage(),
                            "Settings", JOptionPane.ERROR_MESSAGE);
                }
            }
        }
    }
}
