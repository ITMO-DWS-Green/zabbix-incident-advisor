package ru.itmo.advisor.zabbix.model;

/** Биты {@code action} для event.acknowledge (ZBX_PROBLEM_UPDATE_* в defines.inc.php). */
public final class ProblemUpdate {
    public static final int CLOSE = 0x01;
    public static final int ACKNOWLEDGE = 0x02;
    public static final int MESSAGE = 0x04;
    public static final int SEVERITY = 0x08;
    public static final int UNACKNOWLEDGE = 0x10;
    public static final int SUPPRESS = 0x20;
    public static final int UNSUPPRESS = 0x40;
    public static final int RANK_TO_CAUSE = 0x80;
    public static final int RANK_TO_SYMPTOM = 0x100;

    private ProblemUpdate() {
    }
}
