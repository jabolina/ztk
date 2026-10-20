package io.github.jabolina.ztk.cli.completers;

import java.util.Collection;
import java.util.List;

import org.aesh.command.Command;
import org.aesh.command.completer.CompleterInvocation;
import org.aesh.console.AeshContext;
import org.aesh.terminal.formatting.TerminalString;

import io.github.jabolina.ztk.Context;

/**
 * The invocation every ztk completer receives: Æsh's own completer invocation, plus the ztk {@link Context}.
 *
 * <p>
 * Completers compute their candidates from the same configuration and Taskwarrior data as the command they complete,
 * so a candidate is always a value the command accepts. All Æsh behavior is delegated unchanged.
 * </p>
 *
 * @since 1.0
 * @author José Bolina
 */
public final class ZtkCompleterInvocation implements CompleterInvocation {

   private final CompleterInvocation delegate;
   private final Context context;

   /**
    * Wraps an Æsh completer invocation.
    *
    * @param delegate the invocation created by Æsh.
    * @param context the dependencies of the running ztk invocation.
    */
   public ZtkCompleterInvocation(CompleterInvocation delegate, Context context) {
      this.delegate = delegate;
      this.context = context;
   }

   /**
    * @return the dependencies of the running ztk invocation.
    */
   public Context context() {
      return context;
   }

   /**
    * Offers the candidates that complete the word being typed.
    *
    * <p>
    * Æsh shows a completer's values as they are, so the candidates that do not start with the typed word are left
    * out here. Nothing typed yet offers every candidate.
    * </p>
    *
    * @param candidates every value the argument accepts, e.g. all task IDs.
    */
   public void offer(Collection<String> candidates) {
      String typed = getGivenCompleteValue();
      for (String candidate : candidates) {
         if (candidate.startsWith(typed)) {
            addCompleterValue(candidate);
         }
      }
   }

   @Override
   public String getGivenCompleteValue() {
      return delegate.getGivenCompleteValue();
   }

   @Override
   @SuppressWarnings("rawtypes")
   public Command getCommand() {
      return delegate.getCommand();
   }

   @Override
   public List<TerminalString> getCompleterValues() {
      return delegate.getCompleterValues();
   }

   @Override
   public void setCompleterValues(Collection<String> completerValues) {
      delegate.setCompleterValues(completerValues);
   }

   @Override
   public void setCompleterValuesTerminalString(List<TerminalString> completerValues) {
      delegate.setCompleterValuesTerminalString(completerValues);
   }

   @Override
   public void clearCompleterValues() {
      delegate.clearCompleterValues();
   }

   @Override
   public void addAllCompleterValues(Collection<String> completerValues) {
      delegate.addAllCompleterValues(completerValues);
   }

   @Override
   public void addCompleterValue(String value) {
      delegate.addCompleterValue(value);
   }

   @Override
   public void addCompleterValueTerminalString(TerminalString value) {
      delegate.addCompleterValueTerminalString(value);
   }

   @Override
   public boolean isAppendSpace() {
      return delegate.isAppendSpace();
   }

   @Override
   public void setAppendSpace(boolean appendSpace) {
      delegate.setAppendSpace(appendSpace);
   }

   @Override
   public void setIgnoreOffset(boolean ignoreOffset) {
      delegate.setIgnoreOffset(ignoreOffset);
   }

   @Override
   public boolean doIgnoreOffset() {
      return delegate.doIgnoreOffset();
   }

   @Override
   public void setOffset(int offset) {
      delegate.setOffset(offset);
   }

   @Override
   public int getOffset() {
      return delegate.getOffset();
   }

   @Override
   public void setIgnoreStartsWith(boolean ignoreStartsWith) {
      delegate.setIgnoreStartsWith(ignoreStartsWith);
   }

   @Override
   public boolean isIgnoreStartsWith() {
      return delegate.isIgnoreStartsWith();
   }

   @Override
   public AeshContext getAeshContext() {
      return delegate.getAeshContext();
   }
}
