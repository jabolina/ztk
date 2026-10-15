package io.github.jabolina.ztk.tasks;

import java.nio.file.Path;

import io.github.jabolina.ztk.taskwarrior.Task;

/**
 * A task together with its note.
 *
 * @param task The task, as stored by Taskwarrior after linking the note.
 * @param note The absolute path of the task's note.
 */
public record TaskNote(Task task, Path note) { }
