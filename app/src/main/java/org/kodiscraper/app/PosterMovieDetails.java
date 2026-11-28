package org.kodiscraper.app;

import android.content.res.Configuration;
import android.os.Build;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

final class PosterMovieDetails {
    private static final int INITIAL_CAST = 5;

    private final MainActivity activity;
    private final AppAppearance appearance;
    private final ScrapeEngine.LibraryMovie movie;
    private final LinearLayout facts;
    private final LinearLayout sections;

    PosterMovieDetails(MainActivity activity, LinearLayout content, ScrapeEngine.LibraryMovie movie,
                       MoviePosterLoader posters) {
        this.activity = activity;
        this.appearance = activity.appearance();
        this.movie = movie;

        Configuration configuration = activity.getResources().getConfiguration();
        boolean stacked = configuration.screenWidthDp < 360 && configuration.fontScale > 1.25f;
        LinearLayout header = stacked ? activity.vertical() : activity.horizontal();
        ImageView poster = new ImageView(activity);
        poster.setContentDescription("电影海报");
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackground(activity.shape(appearance.tint, 11, appearance.border));
        poster.setClipToOutline(true);
        header.addView(poster, new LinearLayout.LayoutParams(activity.dp(88), activity.dp(132)));
        facts = activity.vertical();
        LinearLayout.LayoutParams factSpace = stacked
                ? new LinearLayout.LayoutParams(-1, -2)
                : new LinearLayout.LayoutParams(0, -2, 1f);
        if (stacked) factSpace.topMargin = activity.dp(8);
        else factSpace.setMarginStart(activity.dp(16));
        header.addView(facts, factSpace);
        activity.add(content, header, 0);
        posters.bindLocal(poster, movie.poster());

        sections = activity.vertical();
        activity.add(content, sections, 10);
        renderFacts(Collections.emptyList());
        line(sections, "正在读取影片资料…", 14, appearance.muted, false, 10);
    }

    void render(Element nfo, Consumer<TrailerPlayer.Source> play) {
        List<Element> fields = KodiNfoPreservation.children(nfo);
        renderFacts(fields);
        sections.removeAllViews();

        TrailerPlayer.Source trailer = TrailerPlayer.source(movie.localTrailer(), value(fields, "trailer"));
        if (trailer != null) {
            Button button = activity.button("播放预告片", appearance.primary, appearance.onPrimary);
            button.setMinHeight(activity.dp(48));
            button.setOnClickListener(view -> play.accept(trailer));
            activity.add(sections, button, 4);
        }

        line(sections, value(fields, "tagline"), 15, appearance.foreground, true, 14);
        String plot = value(fields, "plot");
        section("剧情简介", plot.isEmpty() ? value(fields, "outline") : plot);
        section("导演", String.join("、", values(fields, "director")));
        section("编剧", String.join("、", values(fields, "credits")));
        cast(fields);
        Element set = field(fields, "set");
        if (set != null) section("合集", value(KodiNfoPreservation.children(set), "name"));
        UiMotion.reveal(sections);
    }

    void showError() {
        sections.removeAllViews();
        line(sections, "无法读取这部电影的本地资料。", 14, appearance.muted, false, 10);
    }

    private void renderFacts(List<Element> fields) {
        facts.removeAllViews();
        String original = value(fields, "originaltitle");
        if (!original.equalsIgnoreCase(movie.title())) {
            line(facts, original, 16, appearance.foreground, true, 0);
        }

        List<String> details = new ArrayList<>();
        String date = value(fields, "premiered");
        if (!date.isEmpty()) details.add(date);
        else if (movie.year() > 0) details.add(Integer.toString(movie.year()));
        String runtime = value(fields, "runtime");
        if (!runtime.isEmpty()) details.add(runtime + " 分钟");
        String ageRating = value(fields, "mpaa");
        if (!ageRating.isEmpty()) details.add(ageRating);
        line(facts, String.join(" · ", details), 14, appearance.muted, false, 5);
        line(facts, String.join(" · ", movie.genres()), 14, appearance.muted, false, 5);

        String score = rating(fields);
        if (!score.isEmpty()) {
            TextView badge = activity.text(score, 13, appearance.accent, true);
            badge.setPadding(activity.dp(8), activity.dp(4), activity.dp(8), activity.dp(4));
            badge.setBackground(activity.shape(appearance.tint, 8, 0));
            LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-2, -2);
            space.topMargin = activity.dp(9);
            facts.addView(badge, space);
        }
        facts.setVisibility(facts.getChildCount() == 0 ? View.GONE : View.VISIBLE);
    }

    private void cast(List<Element> fields) {
        List<String> cast = new ArrayList<>();
        for (Element actor : fields) {
            if (!"actor".equals(actor.getTagName())) continue;
            List<Element> actorFields = KodiNfoPreservation.children(actor);
            String name = value(actorFields, "name");
            String role = value(actorFields, "role");
            if (!name.isEmpty()) cast.add(role.isEmpty() ? name : name + " 饰 " + role);
        }
        if (cast.isEmpty()) return;

        heading("演员");
        int first = Math.min(cast.size(), INITIAL_CAST);
        for (int index = 0; index < first; index++) {
            line(sections, cast.get(index), 14, appearance.foreground, false, 4);
        }
        if (cast.size() <= first) return;

        String expand = "展开全部演员（" + cast.size() + " 人）";
        Button toggle = activity.button(expand, appearance.tint, appearance.accent);
        toggle.setMinHeight(activity.dp(48));
        activity.add(sections, toggle, 8);

        LinearLayout additional = activity.vertical();
        additional.setVisibility(View.GONE);
        activity.add(sections, additional, 4);
        setCastExpansion(toggle, expand, false);
        toggle.setOnClickListener(view -> {
            boolean opening = additional.getVisibility() != View.VISIBLE;
            if (opening && additional.getChildCount() == 0) {
                for (int index = first; index < cast.size(); index++) {
                    line(additional, cast.get(index), 14, appearance.foreground, false, 4);
                }
            }
            additional.setVisibility(opening ? View.VISIBLE : View.GONE);
            setCastExpansion(toggle, expand, opening);
            if (opening) UiMotion.reveal(additional);
        });
    }

    private static void setCastExpansion(Button toggle, String expand, boolean expanded) {
        toggle.setText(expanded ? "收起演员" : expand);
        if (Build.VERSION.SDK_INT >= 30) {
            toggle.setStateDescription(expanded ? "已展开" : "已收起");
        }
    }

    private void section(String title, String text) {
        if (text.isEmpty()) return;
        heading(title);
        line(sections, text, 14, appearance.foreground, false, 5);
    }

    private void heading(String value) {
        TextView heading = activity.text(value, 16, appearance.foreground, true);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        activity.add(sections, heading, 17);
    }

    private void line(LinearLayout parent, String text, int size, int color, boolean bold, int margin) {
        if (text.isEmpty()) return;
        TextView view = activity.text(text, size, color, bold);
        view.setLineSpacing(activity.dp(3), 1f);
        activity.add(parent, view, margin);
    }

    private static String rating(List<Element> fields) {
        Element ratings = field(fields, "ratings");
        if (ratings != null) {
            Element selected = null;
            for (Element rating : KodiNfoPreservation.children(ratings)) {
                if (!"rating".equals(rating.getTagName())) continue;
                if (selected == null) selected = rating;
                if ("true".equalsIgnoreCase(rating.getAttribute("default"))) {
                    selected = rating;
                    break;
                }
            }
            if (selected != null) {
                List<Element> score = KodiNfoPreservation.children(selected);
                String value = value(score, "value");
                String source = selected.getAttribute("name").trim();
                String max = selected.getAttribute("max").trim();
                if (!value.isEmpty()) return formatRating(source, value, max, value(score, "votes"));
            }
        }
        return formatRating("", value(fields, "rating"), "", value(fields, "votes"));
    }

    private static String formatRating(String source, String value, String max, String votes) {
        if (value.isEmpty()) return "";
        String label = switch (source.toLowerCase(Locale.ROOT)) {
            case "tmdb", "themoviedb" -> "TMDB";
            case "tvdb", "thetvdb" -> "TheTVDB";
            case "imdb" -> "IMDb";
            case "", "default" -> "评分";
            default -> source;
        };
        return label + " " + value + (max.isEmpty() ? "" : " / " + max)
                + (votes.isEmpty() ? "" : " · " + votes + " 票");
    }

    private static Element field(List<Element> fields, String name) {
        for (Element field : fields) {
            if (field.getTagName().equals(name)) return field;
        }
        return null;
    }

    private static String value(List<Element> fields, String name) {
        Element field = field(fields, name);
        return field == null ? "" : field.getTextContent().trim();
    }

    private static List<String> values(List<Element> fields, String name) {
        List<String> values = new ArrayList<>();
        for (Element field : fields) {
            if (!field.getTagName().equals(name)) continue;
            if ("true".equalsIgnoreCase(field.getAttribute("clear"))) values.clear();
            String text = field.getTextContent().trim();
            if (!text.isEmpty()) values.add(text);
        }
        return values;
    }
}
