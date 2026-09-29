package tn.loukious.facebookappadsremover.hooks

/**
 * A feed filter decision does not depend on how Facebook delivered the item.
 * The classic-feed, CSR cache, late-list and Litho hookers only adapt Facebook's
 * input/output types; all of them evaluate these same rules.
 *
 * Unknown/null items are retained (fail open). A rule must positively identify
 * an item before a pipeline is allowed to drop it.
 */
internal interface FeedItemSignals {
    fun category(item: Any): String?
    fun sponsored(item: Any): Boolean
    fun aiContent(item: Any): Boolean
    fun searchableText(item: Any): String?
}

internal class FeedItemFacts(val item: Any, private val signals: FeedItemSignals) {
    private var stateMask = 0
    private var _category: String? = null
    private var _searchableText: String? = null

    val category: String?
        get() {
            if ((stateMask and 1) == 0) {
                _category = signals.category(item)
                stateMask = stateMask or 1
            }
            return _category
        }

    val sponsored: Boolean
        get() {
            if ((stateMask and 2) == 0) {
                if (signals.sponsored(item)) stateMask = stateMask or 16
                stateMask = stateMask or 2
            }
            return (stateMask and 16) != 0
        }

    val aiContent: Boolean
        get() {
            if ((stateMask and 4) == 0) {
                if (signals.aiContent(item)) stateMask = stateMask or 32
                stateMask = stateMask or 4
            }
            return (stateMask and 32) != 0
        }

    val searchableText: String?
        get() {
            if ((stateMask and 8) == 0) {
                _searchableText = signals.searchableText(item)
                stateMask = stateMask or 8
            }
            return _searchableText
        }
}

internal interface FeedFilterRule {
    val id: String
    fun matches(item: FeedItemFacts): Boolean
}

internal class FeedFilterEngine(
    private val signals: FeedItemSignals,
    private val rules: List<FeedFilterRule>,
) {
    data class Decision(val ruleId: String?) {
        val remove: Boolean get() = ruleId != null
    }

    data class Partition(
        val kept: List<Any?>,
        val removedByRule: Map<String, Int>,
        val evaluatedByRule: Map<String, Int>,
        val inspected: Int,
    ) {
        val removed: Int get() = removedByRule.values.sum()
    }

    val active: Boolean get() = rules.isNotEmpty()

    fun decide(item: Any?): Decision {
        if (item == null) return Decision(null)
        val facts = FeedItemFacts(item, signals)
        for (rule in rules) {
            if (runCatching { rule.matches(facts) }.getOrDefault(false)) {
                return Decision(rule.id)
            }
        }
        return Decision(null)
    }

    fun partition(items: Iterable<*>): Partition {
        val kept = if (items is Collection<*>) ArrayList<Any?>(items.size) else ArrayList<Any?>()
        val counts = IntArray(rules.size)
        val evaluated = IntArray(rules.size)
        var inspected = 0
        for (item in items) {
            inspected++
            if (item == null) {
                kept.add(null)
                continue
            }
            val facts = FeedItemFacts(item, signals)
            var removed = false
            for (i in rules.indices) {
                evaluated[i]++
                val rule = rules[i]
                if (runCatching { rule.matches(facts) }.getOrDefault(false)) {
                    counts[i]++
                    removed = true
                    break
                }
            }
            if (!removed) {
                kept.add(item)
            }
        }
        
        val countsMap = LinkedHashMap<String, Int>()
        val evaluatedMap = LinkedHashMap<String, Int>()
        for (i in rules.indices) {
            if (counts[i] > 0) countsMap[rules[i].id] = counts[i]
            if (evaluated[i] > 0) evaluatedMap[rules[i].id] = evaluated[i]
        }
        
        return Partition(kept, countsMap, evaluatedMap, inspected)
    }
}
