package com.stealth.rootexecutor;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** A real child process used to detect line-buffered output and stdin regressions. */
public final class PipeFixture {
    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8"));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, "UTF-8"));
        System.out.print("PROMPT:");
        System.out.flush();
        String answer = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        System.out.print(answer == null ? "EOF" : "ANSWER:" + answer);
        System.out.flush();
        System.err.print("STDERR");
        System.err.flush();
        System.exit(7);
    }
}
