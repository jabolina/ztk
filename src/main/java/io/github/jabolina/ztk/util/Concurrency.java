package io.github.jabolina.ztk.util;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public final class Concurrency {

   private static final Executor EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

   private Concurrency() { }

   public static Executor executor() {
      return EXECUTOR;
   }
}
