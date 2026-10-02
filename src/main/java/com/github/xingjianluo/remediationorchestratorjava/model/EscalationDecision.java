package com.github.xingjianluo.remediationorchestratorjava.model;

/**
 * 针对某一告警指纹的人工升级处置决定（持久化，跨重启有效）。
 * <p>
 * 语义：决定只覆盖做出决定时统计窗内的复发轮次 {@code countAtDecision}；
 * 若之后复发计数继续增长（确认后又修了一轮仍复发），会重新触发升级。
 */
public class EscalationDecision {

    public enum Kind {
        NONE, CONFIRMED, ABANDONED
    }

    public String fingerprint;
    public Kind kind = Kind.NONE;
    /** 做出决定时统计窗内的复发计数，决定仅覆盖到该计数。 */
    public int countAtDecision;
    public long decidedAt;
    public String decidedBy = "";
    public String note = "";

    public EscalationDecision() {
    }

    public EscalationDecision(String fingerprint, Kind kind, int countAtDecision,
                              long decidedAt, String decidedBy, String note) {
        this.fingerprint = fingerprint;
        this.kind = kind;
        this.countAtDecision = countAtDecision;
        this.decidedAt = decidedAt;
        this.decidedBy = decidedBy == null ? "" : decidedBy;
        this.note = note == null ? "" : note;
    }
}
