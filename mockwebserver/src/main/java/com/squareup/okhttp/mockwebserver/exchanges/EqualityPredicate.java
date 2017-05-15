package com.squareup.okhttp.mockwebserver.exchanges;

class EqualityPredicate<T> implements Predicate<T> {

    private final T expected;

    public EqualityPredicate(final T expected) {
        this.expected = expected;
    }

    @Override
    public boolean test(final T t) {
        return expected.equals(t);
    }
}
