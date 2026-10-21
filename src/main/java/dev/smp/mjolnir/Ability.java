package dev.smp.mjolnir;

public enum Ability {
    THROW("Mjölnir Throw", "throw", 1.0),
    LIGHTNING("Thunder Strike", "lightning", 2.0),
    STORM("Storm Caller", "storm", 15.0),
    FLIGHT("Thunder Flight", "flight", 5.0),
    GOD_BLAST("God Blast", "god-blast", 50.0),
    FORCE_FIELD("Force Field", "force-field", 15.0),
    BIFROST("Bifröst Portal", "bifrost", 10.0),
    DETECT("Energy Detection", "detect", 10.0);

    public final String display;
    public final String key;
    public final double defaultCooldown;

    Ability(String display, String key, double defaultCooldown) {
        this.display = display;
        this.key = key;
        this.defaultCooldown = defaultCooldown;
    }

    public Ability next() {
        Ability[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
