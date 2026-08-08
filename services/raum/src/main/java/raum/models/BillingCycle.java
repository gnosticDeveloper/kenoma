package raum.models;

import java.time.Period;

public enum BillingCycle {
    MONTHLY(Period.ofMonths(1)),
    QUARTERLY(Period.ofMonths(3)),
    ANNUAL(Period.ofYears(1));

    private final Period period;

    BillingCycle(Period period) {
        this.period = period;
    }

    public Period getPeriod() {
        return period;
    }
}
