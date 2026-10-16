package io.github.jabolina.ztk.taskwarrior;

import java.util.List;

/**
 * A Taskwarrior report to render, described but not run.
 *
 * <p>
 * Use cases describe which report shows their tasks; the caller decides whether and how it is rendered, through
 * {@link TaskStore#render(Report)}.
 * </p>
 *
 * @param name the report name, e.g. {@code next}, or an ad hoc report defined in {@code arguments}.
 * @param arguments the filter terms and {@code rc.} overrides, e.g. {@code "project:Alpha"}.
 * @since 1.0
 * @author José Bolina
 */
public record Report(String name, List<String> arguments) { }
