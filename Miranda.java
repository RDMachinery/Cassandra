import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

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
 * Miranda - a news-driven forecaster.
 *
 * Each run, Miranda reads headlines from RSS feeds, finds the dominant story, and then:
 *   1. fits a trend to that story's share of coverage over past runs and projects it forward;
 *   2. issues explicit, dated, falsifiable predictions with probabilities;
 *   3. writes a plain-English scenario of the likely consequences;
 *   4. grades every earlier prediction that has now fallen due, reports her track record
 *      (hit rate and Brier score), and uses that record to recalibrate future probabilities.
 *
 * Nobody can see the future. What Miranda can do is commit to testable claims, keep score,
 * and correct her own over- or under-confidence over time. Run her daily (e.g. via cron) so
 * her history and track record accumulate.
 *
 * Requires Java 16+. No external libraries.
 *
 * Compile:  javac Miranda.java
 * Run:      java Miranda                      default feeds
 *           java Miranda URL1 URL2 ...        your own RSS feeds
 *           java Miranda --demo               offline synthetic headlines (separate data dir)
 *
 * Options:  --data=DIR      where history/predictions are kept (default ./miranda-data)
 *           --no-log        analyse only; do not read or write any saved state
 *           --asof=DATE     pretend today is DATE (yyyy-mm-dd); for demos and backtests only
 *           --help
 */
public class Miranda {

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

    static Instant NOW = Instant.now();
    static LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    enum Domain { CONFLICT, ECONOMY, TECH, POLITICS, CLIMATE, HEALTH, GENERAL }

    enum Tone { NEGATIVE, MIXED, POSITIVE }

    // ------------------------------------------------------------------ data

    record Article(String source, String title, String body, Instant published) {}

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
        double at(int h) { return fittedToday + slope * h; }
        double predSd(int h) {
            double d = h - xbar;
            return sd * Math.sqrt(1 + 1.0 / n + d * d / sxx);
        }
    }

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
            return OptionalDouble.of(ds.stream().mapToInt(Miranda::netSentiment).average().orElse(0));
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
            says said say new news year years day days week weeks today latest live first last may might must now
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
        return setOf(words).stream().map(Miranda::stem).collect(Collectors.toSet());
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) throws IOException {
        boolean demo = false, log = true;
        String dataArg = null;
        boolean asof = false;
        List<String> feeds = new ArrayList<>();

        for (String a : args) {
            if (a.equals("--demo")) demo = true;
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

        Path dir = Paths.get(dataArg != null ? dataArg : demo ? "miranda-demo-data" : "miranda-data");
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
                Miranda - news-driven forecaster
                  java Miranda [options] [feed-url ...]
                  --demo          offline synthetic headlines (uses ./miranda-demo-data)
                  --data=DIR      directory for history.tsv and predictions.tsv (default ./miranda-data)
                  --no-log        analyse only; do not read or write saved state
                  --asof=DATE     pretend today is DATE (yyyy-mm-dd); demos and backtests only
                """);
    }

    // ------------------------------------------------------------------ fetching

    static List<Article> fetchFeed(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Miranda/1.0 (educational project)")
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
            out.add(new Article(host, title, body, parseDate(childText(item, "pubDate"))));
        }
        return out;
    }

    static String childText(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        return nl.getLength() == 0 ? "" : nl.item(0).getTextContent();
    }

    static String clean(String s) {
        return s.replaceAll("<[^>]*>", " ").replaceAll("&[a-z#0-9]+;", " ").replaceAll("\\s+", " ").trim();
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

    static void report(List<Article> articles, Path dir, boolean log) throws IOException {
        List<Doc> docs = articles.stream().map(Miranda::analyse).toList();

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
        if (scores.isEmpty()) {
            System.out.println("No topic appears in enough articles to call it a trend.");
            return;
        }
        List<String> ranked = scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey).toList();
        List<String> reps = storyReps(ranked, index, 10);

        String topTerm = reps.get(0);
        List<Doc> story = index.get(topTerm);
        Snapshot snap = new Snapshot(index, reps, docs.size());

        String label = bestPhrase(topTerm, story);
        List<String> related = relatedTerms(topTerm, story, 8).stream()
                .filter(w -> !label.contains(w)).limit(3).toList();

        long sources = story.stream().map(d -> d.article().source()).distinct().count();
        double net = story.stream().mapToInt(Miranda::netSentiment).average().orElse(0);
        Tone tone = net <= -0.5 ? Tone.NEGATIVE : net >= 0.5 ? Tone.POSITIVE : Tone.MIXED;
        String momentum = momentum(story);
        Domain domain = classify(story);
        double shareNow = snap.share(topTerm);

        // 2. Saved state: history and predictions
        List<HistRow> history = log ? loadHistory(dir) : new ArrayList<>();
        List<Pred> preds = log ? loadPreds(dir) : new ArrayList<>();

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

        // 4. Trend fit on this story's share of coverage
        Forecast fc = forecast(history, topTerm, shareNow);

        // 5. Issue new predictions
        List<Pred> fresh = makePredictions(topTerm, label, tone, net, momentum, shareNow, history, fc, record);
        Set<String> known = preds.stream().map(Pred::id).collect(Collectors.toSet());
        List<Pred> toAdd = fresh.stream().filter(p -> !known.contains(p.id())).toList();
        if (log) {
            preds.addAll(toAdd);
            saveState(dir, history, preds, reps, snap, topTerm);
        }

        // ---------------------------------------------------------------- output
        String bar = "=".repeat(WRAP);
        System.out.println(bar);
        System.out.println(" MIRANDA - forecast for " + TODAY + ", from " + docs.size() + " headlines");
        System.out.println(bar);

        System.out.println("\nDOMINANT STORY: \"" + label + "\"");
        System.out.println(wrap(String.format(Locale.ROOT,
                "Mentioned in %d of %d articles (%.0f%% of coverage) across %d outlet(s). Domain: %s. "
                        + "Tone: %s (net sentiment %+.2f per article). Momentum today: %s.%s",
                story.size(), docs.size(), shareNow * 100, sources, domain.name().toLowerCase(),
                tone.name().toLowerCase(), net, momentum,
                related.isEmpty() ? "" : " Frequently linked with: " + String.join(", ", related) + "."), ""));

        System.out.println("\nSAMPLE HEADLINES");
        story.stream().sorted(Comparator.comparing((Doc d) -> d.article().published()).reversed())
                .limit(4).forEach(d -> System.out.println(wrap("- " + d.article().title()
                        + " [" + d.article().source() + "]", "  ")));

        // coverage forecast
        System.out.println("\nCOVERAGE FORECAST (share of headlines mentioning this story)");
        if (fc == null) {
            System.out.println(wrap("Not enough history yet to fit a trend (need observations on 3+ different days). "
                    + "Run Miranda daily and this section will fill in.", "  "));
        } else {
            System.out.println(wrap(String.format(Locale.ROOT,
                    "Trend over %d days of data: %+.1f percentage points per day. Today %.0f%%.",
                    fc.n(), fc.slope() * 100, shareNow * 100), "  "));
            for (int h : new int[]{3, 7}) {
                double mid = clamp(fc.at(h), 0, 1), w = 1.2816 * fc.predSd(h);
                System.out.println(String.format(Locale.ROOT, "  In %d days: about %.0f%% (80%% range %.0f%%-%.0f%%)",
                        h, mid * 100, clamp(mid - w, 0, 1) * 100, clamp(mid + w, 0, 1) * 100));
            }
        }

        // predictions
        System.out.println("\nPREDICTIONS (logged and graded when due)");
        for (Pred p : fresh) System.out.println(wrap("- " + statement(p) + "  -> "
                + pct(p.p()) + (p.p() != p.pRaw() ? " [recalibrated from " + pct(p.pRaw()) + "]" : ""), "  "));
        if (!log) System.out.println("  (--no-log: these were not saved and cannot be graded later)");
        else if (toAdd.isEmpty()) System.out.println("  (already logged earlier today)");

        // scenario
        String ctx = related.isEmpty() ? "" : " (touching " + joinNatural(related) + ")";
        String[][] chain = chain(domain);
        int primary = tone == Tone.NEGATIVE ? 0 : tone == Tone.POSITIVE ? 1 : (net <= 0 ? 0 : 1);
        int alt = 1 - primary;

        System.out.println("\nMOST LIKELY SEQUENCE OF EVENTS");
        String[] horizons = {"Next few days", "Coming weeks", "Coming months"};
        for (int i = 0; i < 3; i++) {
            String text = fill(chain[i][primary], label, ctx);
            if (i == 0) text += " " + momentumSentence(momentum);
            System.out.println(wrap(horizons[i] + ": " + text, "  "));
            System.out.println();
        }
        System.out.println("ALTERNATIVE BRANCH");
        System.out.println(wrap("If the early signs above do not hold: " + fill(chain[1][alt], label, ctx)
                + " " + fill(chain[2][alt], label, ctx), "  "));

        double conf = Math.min(1, story.size() / 15.0) * 0.5 + Math.min(1, sources / 4.0) * 0.3
                + (tone == Tone.MIXED ? 0.05 : 0.2);
        System.out.println("\nSCENARIO CONFIDENCE: " + (conf < 0.4 ? "LOW" : conf < 0.7 ? "MODERATE"
                : "FAIRLY HIGH (for a heuristic)"));

        // graded this run + track record
        if (!gradedNow.isEmpty()) {
            System.out.println("\nGRADED TODAY");
            for (Pred p : gradedNow)
                System.out.println(wrap("- [" + (p.outcome().equals("TRUE") ? "CAME TRUE" : "DID NOT HAPPEN") + "] "
                        + statement(p) + " (forecast " + pct(p.p()) + ")", "  "));
        }
        printTrackRecord(record, preds, log);

        System.out.println("\nOTHER STORYLINES TO WATCH");
        for (int i = 1; i < Math.min(4, reps.size()); i++)
            System.out.println(wrap("- " + bestPhrase(reps.get(i), index.get(reps.get(i)))
                    + " (" + index.get(reps.get(i)).size() + " articles)", "  "));

        System.out.println("\nNOTE: Miranda cannot see the future. Her probabilities start as educated guesses and are "
                + "only as good as her track record shows. Do not use her output for financial, political or "
                + "safety decisions.");
    }

    // ------------------------------------------------------------------ predictions

    static List<Pred> makePredictions(String term, String label, Tone tone, double net, String momentum,
                                      double shareNow, List<HistRow> history, Forecast fc,
                                      Map<String, Stat> record) {
        List<Pred> out = new ArrayList<>();

        // persistence: how many consecutive earlier days was this story already in the top 5?
        int streak = 0;
        List<LocalDate> dates = history.stream().map(HistRow::date).filter(d -> d.isBefore(TODAY))
                .distinct().sorted(Comparator.reverseOrder()).toList();
        for (LocalDate d : dates) {
            boolean in = history.stream().anyMatch(r -> r.date().equals(d) && r.term().equals(term) && r.rank() <= 5);
            if (in) streak++; else break;
        }
        double mom = momentum.equals("accelerating") ? 0.10 : momentum.equals("fading") ? -0.15 : 0;
        double top3 = clamp(0.62 + 0.05 * Math.min(streak, 5) + mom, 0.10, 0.92);
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
        System.out.println("\nMIRANDA'S TRACK RECORD");
        long pending = preds.stream().filter(p -> p.outcome().equals("PENDING")).count();
        if (!log) { System.out.println("  (--no-log: no saved state)"); return; }
        if (record.isEmpty()) {
            System.out.println(wrap("No predictions have been graded yet. Predictions fall due 3 and 7 days after they "
                    + "are made, so run Miranda daily and a record will build up. Pending: " + pending + ".", "  "));
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
                overall < 0.25 ? "Miranda is currently beating a coin flip."
                        : "Miranda is NOT currently beating a coin flip - treat her forecasts accordingly.",
                pending), "  "));
        System.out.println(wrap("Types with " + MIN_FOR_CALIBRATION + "+ graded predictions have their probabilities "
                + "automatically recalibrated.", "  "));
    }

    // ------------------------------------------------------------------ trend model

    /** Least-squares line through this story's share of coverage on past run dates plus today. */
    static Forecast forecast(List<HistRow> history, String term, double shareNow) {
        LocalDate from = TODAY.minusDays(HISTORY_DAYS);
        List<LocalDate> dates = history.stream().map(HistRow::date)
                .filter(d -> d.isBefore(TODAY) && !d.isBefore(from)).distinct().sorted().toList();
        List<double[]> pts = new ArrayList<>();
        for (LocalDate d : dates) {
            Optional<HistRow> row = history.stream()
                    .filter(r -> r.date().equals(d) && r.term().equals(term)).findFirst();
            // a story absent from a day's top 10 is treated as having a negligible share
            pts.add(new double[]{ChronoUnit.DAYS.between(TODAY, d), row.map(HistRow::share).orElse(0.0)});
        }
        pts.add(new double[]{0, shareNow});
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
            double net = ds.stream().mapToInt(Miranda::netSentiment).average().orElse(0);
            history.add(new HistRow(TODAY, t, i + 1, ds.size(), snap.total, net));
        }
        history.sort(Comparator.comparing(HistRow::date).thenComparingInt(HistRow::rank));
        List<String> h = new ArrayList<>();
        for (HistRow r : history)
            h.add(String.join("\t", r.date().toString(), r.term(), String.valueOf(r.rank()),
                    String.valueOf(r.count()), String.valueOf(r.total()),
                    String.format(Locale.ROOT, "%.4f", r.net())));
        Files.write(dir.resolve("history.tsv"), h, StandardCharsets.UTF_8);

        List<String> p = new ArrayList<>();
        for (Pred x : preds)
            p.add(String.join("\t", x.id(), x.made().toString(), x.due().toString(), x.term(), x.label(), x.kind(),
                    x.param(), String.format(Locale.ROOT, "%.4f", x.pRaw()),
                    String.format(Locale.ROOT, "%.4f", x.p()), x.outcome(), x.resolved()));
        Files.write(dir.resolve("predictions.tsv"), p, StandardCharsets.UTF_8);
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

    static String wrap(String text, String indent) {
        StringBuilder out = new StringBuilder();
        StringBuilder line = new StringBuilder(indent);
        for (String word : text.split(" ")) {
            if (line.length() + word.length() + 1 > WRAP && line.length() > indent.length()) {
                out.append(line).append('\n');
                line = new StringBuilder(indent);
            }
            if (line.length() > indent.length()) line.append(' ');
            line.append(word);
        }
        return out.append(line).toString();
    }

    // ------------------------------------------------------------------ demo data

    static List<Article> demoArticles() {
        Instant n = NOW;
        String[][] rows = {
                {"demo-wire-a", "Port strike halts shipping as talks collapse", "Dockworkers walked out; supply chain delays warned across retailers", "1"},
                {"demo-wire-a", "Retailers warn of shortages as port strike enters second day", "Shipping firms reroute cargo, prices expected to rise", "2"},
                {"demo-wire-b", "Port strike threatens holiday supply, business groups say", "Business leaders fear damage to growth as shipping stalls", "2"},
                {"demo-wire-b", "Union rejects offer as port strike deepens", "Talks collapse again; shipping delays spread", "3"},
                {"demo-wire-c", "Markets slide as port strike hits shipping stocks", "Shares fall, analysts warn of inflation risk from supply delays", "3"},
                {"demo-wire-c", "Government urged to intervene in port strike", "Ministers face pressure as shortages loom and prices rise", "4"},
                {"demo-wire-a", "Port strike: what it means for prices", "Economists warn of a hit to growth if shipping delays continue", "5"},
                {"demo-wire-b", "Chipmaker unveils new AI processor", "Launch boosts investor hope", "9"},
                {"demo-wire-c", "Council approves new cycling scheme", "Residents welcome plan", "12"},
                {"demo-wire-a", "Local team wins cup final", "Fans celebrate record crowd", "14"}
        };
        List<Article> out = new ArrayList<>();
        for (String[] r : rows)
            out.add(new Article(r[0], r[1], r[2], n.minus(Duration.ofHours(Long.parseLong(r[3])))));
        return out;
    }
}
