package com.cforcoding.jmd;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @author William Shields
 */
public class MarkDownTest {
    private static final String PATH = "functionality/";

    @Test
    public void testInstantiate() {
        new MarkDown();
    }

    private final List<String> explicitTests = Arrays.asList("Images");

    @DataProvider(name = "ExplicitTests")
    public Object[][] explicitTests() throws Exception {
        return tests(explicitTests, true);
    }

    @DataProvider(name = "MarkDown")
    public Object[][] loadTests() throws Exception {
        String[] files = ResourceUtils.getResourceListing(getClass(), PATH);
        List<String> tests = new ArrayList<String>(files.length / 2);
        for (String file : files) {
            if (file.endsWith(".text")) {
                String test = file.replaceAll(".text$", "");
                if (!explicitTests.contains(test)) {
                    tests.add(test);
                }
            }
        }
        return tests(tests);
    }

    private Object[][] tests(List<String> tests, boolean... strictness) throws Exception {
        if (strictness.length == 0) {
            strictness = new boolean[]{ true, false };
        }
        Object[][] ret = new Object[tests.size() * strictness.length][];
        int i = 0;
        for (String test : tests) {
            String text = ResourceUtils.getResourceAsString(getClass(), PATH + test + ".text");
            String html = ResourceUtils.getResourceAsString(getClass(), PATH + test + ".html");
            for (boolean b : strictness) {
                ret[i++] = new Object[]{test, b, text, html};
            }
        }
        return ret;
    }

    @Test(dataProvider = "ExplicitTests", dependsOnMethods = {"testInstantiate"})
    public void testExplicit(String name, boolean strict, String text, String html) {
        runTest(name, strict, text, html);
    }

    @Test(dataProvider = "MarkDown", dependsOnMethods = {"testInstantiate", "testExplicit"})
    public void testMarkDown(String name, boolean strict, String text, String html) {
        runTest(name, strict, text, html);
    }

    private void runTest(String name, boolean strict, String text, String html) {
        MarkDown MarkDown = new MarkDown();
        String output = MarkDown.transform(text);
        boolean test = same(html, output, strict);
        assert test : name + " output not as expected";
    }

    private boolean same(String s1, String s2, boolean strict) {
        if (strict) {
            s1 = removeWhitespace(s1);
            s2 = removeWhitespace(s2);
        }
        return s1.equals(s2);
    }

    private String removeWhitespace(String s) {
        return s.replaceAll("^\\n|\\s+", "");
    }
}
