package com.loki.data;
public enum Discipline {
    MISCHIEF("Mischief",0x72bb82), SORCERY("Sorcery",0x49d091), CONJURATION("Conjuration",0xd0b675), ENCHANTMENT("Enchantment",0x9bca8f), TEMPORAL("Temporal Mastery",0xdfa763), PURPOSE("Glorious Purpose",0xb9d7aa);
    public final String title; public final int color;
    Discipline(String title,int color) { this.title=title;this.color=color; }
}
