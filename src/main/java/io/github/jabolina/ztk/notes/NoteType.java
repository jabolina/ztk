package io.github.jabolina.ztk.notes;

import io.github.jabolina.ztk.util.Labeled;

/**
 * The kind of a note, given by the folder it lives in.
 *
 * @since 1.0
 * @author José Bolina
 */
public enum NoteType implements Labeled {
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
    * A yearly or quarterly goal: a note directly in its level of the goals folder, named after the goal's ID.
    */
   GOAL,

   /**
    * An effort: a note directly in the efforts folder, named after the effort's slug.
    */
   EFFORT,

   /**
    * A note in no folder ztk gives a meaning to, e.g. at the root of the knowledge base, or material kept next to a
    * goal or an effort.
    */
   OTHER;
}
