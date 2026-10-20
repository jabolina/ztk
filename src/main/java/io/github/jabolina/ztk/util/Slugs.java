package io.github.jabolina.ztk.util;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Slugs: the lowercase, dash-separated names files and efforts are given, e.g. {@code fix-flaky-test}.
 *
 * @since 1.0
 * @author José Bolina
 */
public final class Slugs {

   // Lowercase words of letters and digits, joined by single dashes.
   private static final Pattern VALID = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

   private Slugs() { }

   /**
    * Turns any text into a slug.
    *
    * <p>
    * Same rule as the original shell script: lower case, every run of characters other than ASCII letters and digits
    * becomes a single {@code -}, without leading or trailing dashes.
    * </p>
    *
    * @param text the text, e.g. a title.
    * @return the slug, e.g. {@code fix-flaky-test}; empty when the text has no ASCII letters or digits.
    */
   public static String of(String text) {
      return text.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-|-$", "");
   }

   /**
    * Turns any text into a slug of at most {@code max} characters.
    *
    * @param text the text, e.g. a title.
    * @param max the longest slug to return.
    * @return the slug, cut without leaving a dash at the end; empty when the text has no ASCII letters or digits.
    */
   public static String of(String text, int max) {
      String slug = of(text);
      if (slug.length() <= max) {
         return slug;
      }
      // Dashes never repeat in a slug, so a cut leaves at most one at the end.
      String cut = slug.substring(0, max);
      return cut.endsWith("-") ? cut.substring(0, max - 1) : cut;
   }

   /**
    * Tells whether a name given by the user is already a slug.
    *
    * @param name the name, as given.
    * @return whether it is lowercase letters and digits, joined by single dashes.
    */
   public static boolean valid(String name) {
      return VALID.matcher(name).matches();
   }
}
