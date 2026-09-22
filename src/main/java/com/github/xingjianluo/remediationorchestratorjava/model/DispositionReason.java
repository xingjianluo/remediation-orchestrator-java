package com.github.xingjianluo.remediationorchestratorjava.model;

/**
 * 一条告警受理结论的可区分原因，便于审计与排查：
 * <ul>
 *   <li>{@link #ACCEPTED} 已接受并生成新任务；</li>
 *   <li>{@link #DUPLICATE_MERGED} 等价告警命中进行中任务，被合并计数；</li>
 *   <li>{@link #DUPLICATE_TERMINAL} 等价告警对应的任务已到终态，不会重复修复；</li>
 *   <li>{@link #SUPPRESSED_MAINTENANCE} 处于维护窗口内被抑制；</li>
 *   <li>{@link #REJECTED_NO_STRATEGY} 没有任何策略可匹配。</li>
 * </ul>
 */
public enum DispositionReason {
    ACCEPTED,
    DUPLICATE_MERGED,
    DUPLICATE_TERMINAL,
    SUPPRESSED_MAINTENANCE,
    REJECTED_NO_STRATEGY
}
