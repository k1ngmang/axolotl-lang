package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@NullMarked public final class Main {
    private Main() {}
    public static void main(String[] args) {
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        final Future<Integer> f = pool.submit(() -> 41 + 1);
        System.out.println(f.isDone());
        pool.shutdown();
        final ScheduledExecutorService sched = Executors.newScheduledThreadPool(1);
        final var h = sched.schedule(() -> System.out.println("tick"), 1L, TimeUnit.MILLISECONDS);
        System.out.println(h.isCancelled());
        final var repeated = sched.scheduleAtFixedRate(() -> System.out.println("tock"), 1L, 1L, TimeUnit.MILLISECONDS);
        System.out.println(repeated.isCancelled());
        sched.shutdown();
        final var fork = ForkJoinPool.commonPool().submit(() -> 2);
        System.out.println(fork.isDone());
        final var lock = new ReentrantLock();
        final var condition = lock.newCondition();
        System.out.println(condition.toString().length() > 0);
    }
}
