import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Miranda - reads news headlines from RSS feeds, finds the dominant story,
 * measures its tone and momentum, and writes a plain-English scenario of what
 * is likely to follow.
 *
 * Requires Java 16+ (records). No external libraries.
 *
 * Compile:  javac NewsOracle.java
 * Run:      java NewsOracle                 (uses default feeds)
 *           java NewsOracle URL1 URL2 ...   (your own RSS feeds)
 *           java NewsOracle --demo          (offline, built-in sample data)
 *
 * IMPORTANT: this is a transparent, rule-based heuristic - not a real
 * forecasting engine. It extrapolates from patterns in headlines.
 * @author Mario Gianota (mariogianota@protonmail.com)
 * This is NOT free software. Please read the license.
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

    enum Domain { CONFLICT, ECONOMY, TECH, POLITICS, CLIMATE, HEALTH, GENERAL }

    enum Tone { NEGATIVE, MIXED, POSITIVE }

    // ------------------------------------------------------------------ data

    record Article(String source, String title, String body, Instant published) {}

    record Doc(Article article, List<String> tokens, Set<String> terms, Set<String> titleTerms) {}

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
            report reports reported video watch analysis opinion editorial per cent amid among set says uk us
            bbc guardian its it's ""it's"" don't isn't won't up out his him she he we i me my is it in on at to of
            a an as be by do if or so no go am are say""");

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
        return setOf(words).stream().map(NewsOracle::stem).collect(Collectors.toSet());
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) {
        List<Article> articles = new ArrayList<>();

        if (args.length > 0 && args[0].equals("--demo")) {
            articles = demoArticles();
            System.out.println("[demo mode: synthetic headlines, not real news]\n");
        } else {
            List<String> feeds = args.length > 0 ? List.of(args) : DEFAULT_FEEDS;
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
        }

        articles = dedupe(articles);
        if (articles.size() < MIN_ARTICLES) {
            System.out.println("Only " + articles.size() + " articles available - not enough signal to say "
                    + "anything meaningful. Check your network connection or feed URLs.");
            return;
        }

        report(articles);
    }

    // ------------------------------------------------------------------ fetching

    static List<Article> fetchFeed(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "NewsOracle/1.0 (educational project)")
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
            return Instant.now();
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

    // ------------------------------------------------------------------ the oracle

    static void report(List<Article> articles) {
        List<Doc> docs = articles.stream().map(Miranda::analyse).toList();

        // 1. Index: term -> documents containing it
        Map<String, List<Doc>> index = new HashMap<>();
        for (Doc d : docs) for (String t : d.terms()) index.computeIfAbsent(t, k -> new ArrayList<>()).add(d);

        // 2. Rank terms: breadth of coverage + number of distinct outlets + headline prominence
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
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey).toList();

        String topTerm = ranked.get(0);
        List<Doc> story = index.get(topTerm);

        // 3. Story label (prefer a two-word phrase if one recurs) and related terms
        String label = bestPhrase(topTerm, story);
        List<String> related = relatedTerms(topTerm, story, 8).stream()
                .filter(w -> !label.contains(w)).limit(3).toList();

        // 4. Signals
        long sources = story.stream().map(d -> d.article().source()).distinct().count();
        double net = story.stream().mapToInt(Miranda::netSentiment).average().orElse(0);
        Tone tone = net <= -0.5 ? Tone.NEGATIVE : net >= 0.5 ? Tone.POSITIVE : Tone.MIXED;
        String momentum = momentum(story);
        Domain domain = classify(story);

        // 5. Output
        String bar = "=".repeat(WRAP);
        System.out.println(bar);
        System.out.println(" NEWS ORACLE - scenario generated from " + docs.size() + " headlines");
        System.out.println(bar);

        System.out.println("\nDOMINANT STORY: \"" + label + "\"");
        System.out.println(wrap(String.format(
                "Mentioned in %d of %d articles across %d outlet(s). Domain: %s. Overall tone: %s "
                        + "(net sentiment %+.2f per article). Coverage momentum: %s.%s",
                story.size(), docs.size(), sources, domain.name().toLowerCase(), tone.name().toLowerCase(),
                net, momentum, related.isEmpty() ? "" : " Frequently linked with: " + String.join(", ", related) + "."),
                ""));

        System.out.println("\nSAMPLE HEADLINES");
        story.stream().sorted(Comparator.comparing((Doc d) -> d.article().published()).reversed())
                .limit(4).forEach(d -> System.out.println(wrap("- " + d.article().title()
                        + " [" + d.article().source() + "]", "  ")));

        String ctx = related.isEmpty() ? "" : " (touching " + joinNatural(related) + ")";
        String[][] chain = chain(domain);
        int primary = tone == Tone.NEGATIVE ? 0 : tone == Tone.POSITIVE ? 1 : (net <= 0 ? 0 : 1);
        int alt = 1 - primary;

        System.out.println("\nPROJECTED SEQUENCE OF EVENTS");
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
        String confLabel = conf < 0.4 ? "LOW" : conf < 0.7 ? "MODERATE" : "FAIRLY HIGH (for a heuristic)";
        System.out.println("\nCONFIDENCE: " + confLabel);

        System.out.println("\nOTHER STORYLINES TO WATCH");
        List<String> shown = new ArrayList<>(List.of(topTerm));
        for (String t : ranked) {
            if (shown.size() >= 4) break;
            if (index.get(t).stream().anyMatch(d -> story.contains(d)) && overlap(index.get(t), story) > 0.6) continue;
            shown.add(t);
            System.out.println(wrap("- " + bestPhrase(t, index.get(t)) + " (" + index.get(t).size() + " articles)", "  "));
        }

        System.out.println("\nNOTE: This tool pattern-matches headlines against hand-written causal templates. "
                + "It cannot know what will actually happen; treat the output as a thought experiment, "
                + "not a prediction you should act on.");
    }

    static double overlap(List<Doc> a, List<Doc> b) {
        long common = a.stream().filter(b::contains).count();
        return (double) common / Math.min(a.size(), b.size());
    }

    static int netSentiment(Doc d) {
        int n = 0;
        for (String t : d.tokens()) {
            if (POS.contains(t)) n++;
            if (NEG.contains(t)) n--;
        }
        return n;
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
        Instant now = Instant.now();
        long recent = story.stream().filter(d -> age(d, now) <= 8).count();
        long earlier = story.stream().filter(d -> age(d, now) > 8 && age(d, now) <= 32).count();
        if (recent + earlier < 4) return "unclear";
        double recentRate = recent / 8.0, earlierRate = earlier / 24.0;
        if (earlierRate == 0) return "accelerating";
        double ratio = recentRate / earlierRate;
        return ratio > 1.3 ? "accelerating" : ratio < 0.7 ? "fading" : "steady";
    }

    static double age(Doc d, Instant now) {
        return Duration.between(d.article().published(), now).toMinutes() / 60.0;
    }

    static String momentumSentence(String m) {
        return switch (m) {
            case "accelerating" -> "Because coverage is accelerating, developments are likely to arrive faster than usual.";
            case "fading" -> "Because coverage is fading, the story may slip from the headlines unless a new development revives it.";
            case "steady" -> "Coverage is steady, so expect a gradual rather than abrupt progression.";
            default -> "There is too little timing data to say whether the pace will quicken or slow.";
        };
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

    // ------------------------------------------------------------------ narrative templates

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
        Instant n = Instant.now();
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
