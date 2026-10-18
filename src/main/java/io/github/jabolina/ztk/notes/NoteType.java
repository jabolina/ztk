package io.github.jabolina.ztk.notes;

import java.util.Locale;

/**
 * The kind of a note, given by the folder it lives in.
 *
 * @since 1.0
 * @author José Bolina
 */
public enum NoteType {
   /**
    * A note in the zettel folder.
    */
   ZETTEL,

   /**
    * A note in the papers folder, usually next to the paper's PDF.
    */
   PAPER,

   /**
    * A note in a project folder that belongs to a task, through {@code task_uuid} in its frontmatter.
    */
   TASK,

   /**
    * A note in a project folder that belongs to no task, e.g. the project's ideas.
    */
   PROJECT,

   /**
    * A note in no folder ztk gives a meaning to, e.g. at the root of the knowledge base.
    */
   OTHER;

   /**
    * @return the name used in JSON results and on the command line, e.g. {@code zettel}.
    */
   public String label() {
      return name().toLowerCase(Locale.ROOT);
   }
}
