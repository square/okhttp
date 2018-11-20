module mockwebserver {
    requires transitive okhttp;
    requires junit;
    exports mockwebserver to junit, guide, slack, staticserver, unixdomainsockets, benchmarks;
}