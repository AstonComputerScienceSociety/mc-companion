package com.astoncss;

import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class MessageFormatter {
    public final static MutableText tag =
        Text.literal("[").formatted(Formatting.WHITE)
        .append(Text.literal("A").formatted(Formatting.RED, Formatting.BOLD))
        .append(Text.literal("C").formatted(Formatting.GREEN, Formatting.BOLD))
        .append(Text.literal("S").formatted(Formatting.GOLD, Formatting.BOLD))
        .append(Text.literal("S").formatted(Formatting.DARK_BLUE, Formatting.BOLD))
        .append(Text.literal("] ").styled(style -> style.withBold(false).withColor(Formatting.WHITE)));

    public static Text makeMessage(String string) {
        return tag.copy().append(Text.literal(string));
    }

    public static Text makeMessage(MutableText text) {
        return tag.copy().append(text);
    }
}
