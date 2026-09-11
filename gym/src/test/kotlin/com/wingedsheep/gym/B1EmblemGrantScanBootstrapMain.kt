package com.wingedsheep.gym

/** Fresh-JVM entry point for the lightweight emblem scan counter. */
fun main() {
    check(System.getProperty("b1.emblemCounter.grandchild") == "true") {
        "Emblem scan counter bootstrap must run as the shadow-jar child"
    }
    runB1EmblemGrantScanCorpusCounterDirect()
}
