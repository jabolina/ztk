package io.github.jabolina.ztk.journal;

import java.nio.file.Path;

import io.github.jabolina.ztk.util.JsonWritable;
import tools.jackson.databind.node.ObjectNode;

/**
 * One work session on one task, as written in the journal.
 *
 * <p>
 * A refresher for the next agent working on the task: what was done, where things are, and what comes next. Each part
 * is a few lines; details belong in the task note.
 * </p>
 *
 * @param id    The session ID: its day and its position in that day's file, e.g. {@code 2026-10-22#1}.
 * @param path  The absolute path of the day's journal file.
 * @param task  The UUID of the task worked on.
 * @param title What the session was about, in one line.
 * @param done  What was done.
 * @param now   Where things are.
 * @param next  What comes next.
 * @since 1.0
 * @author José Bolina
 */
public record Session(String id, Path path, String task, String title, String done, String now, String next)
      implements JsonWritable {

   /**
    * Writes the {@code id}, {@code title}, {@code done}, {@code now} and {@code next}.
    *
    * @param target the JSON object to write into.
    * @return {@code target}, for chaining.
    */
   @Override
   public ObjectNode writeJson(ObjectNode target) {
      target.put("id", id);
      target.put("title", title);
      target.put("done", done);
      target.put("now", now);
      target.put("next", next);
      return target;
   }
}
