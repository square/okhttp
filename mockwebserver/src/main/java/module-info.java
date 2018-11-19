module mockwebserver {
    requires okhttp;
    requires junit;
    exports mockwebserver to junit, guide, slack, staticserver, unixdomainsockets, benchmarks;
}