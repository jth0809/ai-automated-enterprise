package com.aienterprise.backend.tracker.layerb;

import java.net.URI;
import java.util.List;

/** One validated Launch Library 2 response page and its optional continuation. */
public record LaunchPage(List<LaunchRecord> launches, URI next) {

    public LaunchPage {
        launches = List.copyOf(launches);
    }
}
