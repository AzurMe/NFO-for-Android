package org.kodiscraper.app;

import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

final class PosterMovieDetails {
    private final MainActivity activity;
    private final AppAppearance appearance;
    private final LinearLayout content;

    PosterMovieDetails(MainActivity activity, LinearLayout content) {
        this.activity = activity;
        this.appearance = activity.appearance();
        this.content = content;
    }

    void render(Element nfo, ScrapeEngine.LibraryMovie movie, Consumer<TrailerPlayer.Source> play) {
        content.removeAllViews();
        List<Element> fields = KodiNfoPreservation.children(nfo);
        String original = value(fields, "originaltitle");
        if (!original.equalsIgnoreCase(movie.title())) line(original, 16, appearance.foreground, true, 0);
        List<String> facts = new ArrayList<>();
        String date = value(fields, "premiered");
        if (!date.isEmpty()) facts.add(date);
        else if (movie.year() > 0) facts.add(Integer.toString(movie.year()));
        String runtime = value(fields, "runtime");
        if (!runtime.isEmpty()) facts.add(runtime + " 分钟");
        String ageRating = value(fields, "mpaa");
        if (!ageRating.isEmpty()) facts.add(ageRating);
        line(String.join(" · ", facts), 14, appearance.muted, false, 5);
        line(rating(fields), 14, appearance.foreground, true, 6);
        line(String.join(" · ", values(fields, "genre")), 14, appearance.muted, false, 6);
        if (!movie.tags().isEmpty()) line("标签 · " + String.join(" · ", movie.tags()), 13, appearance.muted, false, 6);

        TrailerPlayer.Source trailer = TrailerPlayer.source(movie.localTrailer(), value(fields, "trailer"));
        if (trailer != null) {
            Button button = activity.button("播放预告片", appearance.tint, appearance.accent);
            button.setMinHeight(activity.dp(48));
            button.setOnClickListener(view -> play.accept(trailer));
            activity.add(content, button, 12);
        } else {
            line("暂无可播放的预告片", 13, appearance.muted, false, 10);
        }

        line(value(fields, "tagline"), 15, appearance.foreground, true, 15);
        String plot = value(fields, "plot");
        section("剧情简介", plot.isEmpty() ? value(fields, "outline") : plot);
        section("导演", String.join("、", values(fields, "director")));
        section("编剧", String.join("、", values(fields, "credits")));
        List<String> cast = new ArrayList<>();
        for (Element actor : fields) {
            if (!"actor".equals(actor.getTagName())) continue;
            List<Element> actorFields = KodiNfoPreservation.children(actor);
            String name = value(actorFields, "name");
            String role = value(actorFields, "role");
            if (!name.isEmpty()) cast.add(role.isEmpty() ? name : name + " 饰 " + role);
        }
        section("演员", String.join("\n", cast));
        section("制片地区", String.join("、", values(fields, "country")));
        section("制片公司", String.join("、", values(fields, "studio")));
        Element set = field(fields, "set");
        if (set != null) section("合集", value(KodiNfoPreservation.children(set), "name"));
    }

    private void section(String title, String text) {
        if (text.isEmpty()) return;
        line(title, 15, appearance.foreground, true, 14);
        line(text, 14, appearance.muted, false, 4);
    }

    private void line(String text, int size, int color, boolean bold, int margin) {
        if (text.isEmpty()) return;
        TextView view = activity.text(text, size, color, bold);
        view.setLineSpacing(activity.dp(3), 1f);
        activity.add(content, view, margin);
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
        return (source.isEmpty() ? "评分 " : source + " ") + value + (max.isEmpty() ? "" : " / " + max)
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
