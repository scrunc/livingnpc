package dev.servereer.livingshopkeeper.dialog;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Map;

/**
 * One utterance the shopkeeper can say: a subtitle {@code text} (with {@code <player>}/{@code <npc>}/
 * {@code <time>} placeholders) and an optional {@code voice} clip id. {@code weight} biases random
 * selection within a node; {@code when} narrows when this specific line is eligible; {@code mood} is a
 * free tag consumed later (P6) for emotes/particles.
 */
public final class DialogLine {

    public final String text;
    public final String voice;   // clip id (voice/<id>.wav) or null = silent (subtitle only)
    public final int weight;
    public final String mood;
    public final Conditions when;

    public DialogLine(String text, String voice, int weight, String mood, Conditions when) {
        this.text = text;
        this.voice = voice;
        this.weight = Math.max(1, weight);
        this.mood = mood;
        this.when = when == null ? Conditions.ALWAYS : when;
    }

    /** Parse one entry of a node's {@code lines:} list — either a bare string or a map. */
    static DialogLine parse(Object raw) {
        if (raw instanceof String s) {
            return new DialogLine(s, null, 1, null, Conditions.ALWAYS);
        }
        Map<?, ?> m = asMap(raw);
        if (m == null) return null;
        String text = m.get("text") == null ? "" : String.valueOf(m.get("text"));
        String voice = m.get("voice") == null ? null : String.valueOf(m.get("voice"));
        int weight = m.get("weight") instanceof Number n ? n.intValue() : 1;
        String mood = m.get("mood") == null ? null : String.valueOf(m.get("mood"));
        Conditions when = Conditions.parse(m.get("when"));
        if (text.isBlank() && voice == null) return null;
        return new DialogLine(text, voice, weight, mood, when);
    }

    private static Map<?, ?> asMap(Object raw) {
        if (raw instanceof Map<?, ?> m) return m;
        if (raw instanceof ConfigurationSection cs) return cs.getValues(false);
        return null;
    }
}
