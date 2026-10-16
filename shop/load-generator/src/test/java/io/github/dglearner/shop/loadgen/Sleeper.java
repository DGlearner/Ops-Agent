package io.github.dglearner.shop.loadgen;

/** A child process for {@link ScenarioRunnerTest}: prints a line, sleeps {@code args[0]} seconds, exits {@code args[1]}. */
public final class Sleeper {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("sleeping");
        Thread.sleep(Long.parseLong(args[0]) * 1000);
        System.exit(Integer.parseInt(args[1]));
    }

    private Sleeper() {
    }
}
