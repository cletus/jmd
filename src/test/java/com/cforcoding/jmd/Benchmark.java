package com.cforcoding.jmd;

import java.util.Arrays;
import java.util.List;

/**
 * @author William Shields
 */
public class Benchmark {
    private static final String PATH = "benchmark/";
    private static final List<Test> TESTS = Arrays.asList(
            new Test("markdown-example-short-1.text", 4000),
            new Test("markdown-example-medium-1.text", 1000),
            new Test("markdown-example-long-2.text", 100),
            new Test("markdown-readme.text", 1),
            new Test("markdown-readme.8.text", 1),
            new Test("markdown-readme.32.text", 1)
    );

    public static void main(String args[]) {
        for (Test test : TESTS) {
            try {
                benchmark(test.test, test.count);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static void benchmark(String test, int count) throws Exception {
        String text = ResourceUtils.getResourceAsString(Benchmark.class, PATH + test);
        MarkDown markdown = new MarkDown();
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            String html = markdown.transform(text);
        }
        long end = System.nanoTime();
        System.out.printf("input string length: %d%n", text.length());
        double time = (end - start) / 1000000000.0d;
        if (count == 1) {
            System.out.printf("1 iteration in %,.3f seconds%n", time);
        } else {
            double time2 = (end - start) / 1000000.0d / count;
            System.out.printf("%d iterations in %,.3f seconds (%,.3f ms per iteration)%n", count, time, time2);
        }
    }

    private static class Test {
        public final String test;
        public final int count;

        private Test(String test, int count) {
            this.test = test;
            this.count = count;
        }
    }
}
