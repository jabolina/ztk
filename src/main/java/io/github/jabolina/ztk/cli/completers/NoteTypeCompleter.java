package io.github.jabolina.ztk.cli.completers;

import io.github.jabolina.ztk.notes.NoteType;
import io.github.jabolina.ztk.util.Labeled;

/**
 * Completes a {@code --type} value with the labels of the note types, e.g. {@code zettel}.
 *
 * @since 1.0
 * @author José Bolina
 */
public class NoteTypeCompleter implements ZtkCompleter {

   @Override
   public void complete(ZtkCompleterInvocation completerInvocation) {
      completerInvocation.offer(Labeled.labels(NoteType.class));
   }
}
