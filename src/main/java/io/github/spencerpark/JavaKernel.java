/**
 * The MIT License (MIT)
 * <p>
 * Copyright (c) 2017 Spencer Park
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.github.spencerpark;

import io.github.spencerpark.jupyter.kernel.BaseKernel;
import io.github.spencerpark.jupyter.kernel.LanguageInfo;
import io.github.spencerpark.jupyter.kernel.ReplacementOptions;
import io.github.spencerpark.jupyter.kernel.util.CharPredicate;
import io.github.spencerpark.jupyter.messages.MIMEBundle;
import jdk.jshell.*;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class JavaKernel extends BaseKernel {
    private static OutputStream stdout = new LazyOutputStreamDelegate(() -> System.out);
    private static OutputStream stderr = new LazyOutputStreamDelegate(() -> System.err);
    private static final CharPredicate IDENTIFIER_CHAR = CharPredicate.builder()
            .inRange('a', 'z')
            .inRange('A', 'Z')
            .inRange('0', '9')
            .match('_')
            .build();
    private static final CharPredicate WS = CharPredicate.anyOf(" \t\n\r");

    private final JShell shell;
    private final SourceCodeAnalysis sourceAnalyzer;

    private final LanguageInfo languageInfo;
    private final String banner;
    private final List<LanguageInfo.Help> helpLinks;

    public JavaKernel() {
        this.shell = JShell.builder()
                .out(new PrintStream(stdout))
                .err(new PrintStream(stderr))
                .build();
        this.sourceAnalyzer = this.shell.sourceCodeAnalysis();
        this.languageInfo = new LanguageInfo.Builder("Java")
                .version(Runtime.version().toString())
                .mimetype("text/x-java-source")
                .fileExtension(".java")
                .pygments("java")
                .codemirror("java")
                .build();
        this.banner = String.format("Java %s", Runtime.version());
        this.helpLinks = List.of(
                new LanguageInfo.Help("Java", "https://docs.oracle.com/javase/tutorial/java/nutsandbolts/index.html")
        );
    }

    @Override
    public LanguageInfo getLanguageInfo() {
        return this.languageInfo;
    }

    @Override
    public String getBanner() {
        return this.banner;
    }

    @Override
    public List<LanguageInfo.Help> getHelpLinks() {
        return this.helpLinks;
    }

    private SourceCodeAnalysis.CompletionInfo analyzeCompletion(String source) {
        return this.sourceAnalyzer.analyzeCompletion(source);
    }

    private void printDiagnostics(Snippet snippet) {
        this.shell.diagnostics(snippet)
                .map(d -> d.getMessage(Locale.getDefault()))
                .forEach(msg -> {
                    for (String line : NEWLINE_PATTERN.split(msg)) {
                        if (!line.trim().startsWith("location:"))
                            System.err.println(line);
                    }
                });
    }

    @Override
    public MIMEBundle eval(String expr) throws Exception {
        String lastEvalResult = null;
        SourceCodeAnalysis.CompletionInfo info;
        for (info = analyzeCompletion(expr); info.completeness().isComplete(); info = analyzeCompletion(info.remaining())) {
            String src = info.source();
            for (SnippetEvent event : this.shell.eval(src)) {
                if (event.causeSnippet() == null) {
                    // Fresh snippet
                    JShellException e = event.exception();
                    if (e != null) {
                        if (e instanceof EvalException) {
                            System.err.println(((EvalException) e).getExceptionClassName());
                            printDiagnostics(event.snippet());
                        } else if (e instanceof UnresolvedReferenceException) {
                            printDiagnostics(((UnresolvedReferenceException) e).getSnippet());
                        }
                        throw e;
                    }

                    if (!event.status().isDefined()) {
                        printDiagnostics(event.snippet());
                        throw new IllegalArgumentException("Cannot compile '" + event.snippet().source().trim() + "'");
                    }

                    lastEvalResult = event.snippet().kind() == Snippet.Kind.EXPRESSION
                            ? event.value()
                            : null;
                }
            }
        }

        if (info.completeness() != SourceCodeAnalysis.Completeness.EMPTY) {
            // There is source that was not evaluated because it was not complete
            // TODO raise a better exception
            throw new IllegalArgumentException(String.format("Incomplete source code: '%s'", info.remaining()));
        }

        return lastEvalResult == null || lastEvalResult.isEmpty() ? null : new MIMEBundle(lastEvalResult);
    }

    @Override
    public MIMEBundle inspect(String code, int at, boolean extraDetail) {
        // Move the code position to the end of the identifier to make the inspection work at any
        // point in the identifier. i.e "System.o|ut" or "System.out|" will return the same result.
        while (at + 1 < code.length() && IDENTIFIER_CHAR.test(code.charAt(at + 1))) at++;

        // If the next non-whitespace character is an opening paren '(' then this must be included
        // in the documentation search to ensure it searches for a method call.
        int parenIdx = at;
        while (parenIdx + 1 < code.length() && WS.test(code.charAt(parenIdx + 1))) parenIdx++;
        if (parenIdx + 1 < code.length() && code.charAt(parenIdx + 1) == '(') at = parenIdx + 1;

        List<SourceCodeAnalysis.Documentation> documentations = this.sourceAnalyzer.documentation(code, at + 1, true);
        if (documentations == null || documentations.isEmpty()) {
            return null;
        }

        MIMEBundle fmtDocs = new MIMEBundle(
                documentations.stream()
                        .map(doc -> {
                            String formatted = doc.signature();

                            String javadoc = doc.javadoc();
                            if (javadoc != null) formatted += '\n' + javadoc;

                            return formatted;
                        }).collect(Collectors.joining("\n\n")
                )
        );

        fmtDocs.putHTML(
                documentations.stream()
                        .map(doc -> {
                            String formatted = doc.signature();

                            // TODO consider compiling the javadoc to html for pretty printing
                            String javadoc = doc.javadoc();
                            if (javadoc != null) formatted += "<br/>" + javadoc;

                            return formatted;
                        }).collect(Collectors.joining("<br/><br/>")
                )
        );

        return fmtDocs;
    }

    @Override
    public ReplacementOptions complete(String code, int at) {
        int[] replaceStart = new int[1]; // As of now this is always the same as the cursor...
        List<SourceCodeAnalysis.Suggestion> suggestions = this.sourceAnalyzer.completionSuggestions(code, at, replaceStart);
        if (suggestions == null || suggestions.isEmpty()) return null;

        List<String> options = suggestions.stream()
                .sorted((s1, s2) ->
                        s1.matchesType()
                                ? s2.matchesType() ? 0 : -1
                                : s2.matchesType() ? 1 : 0
                )
                .map(SourceCodeAnalysis.Suggestion::continuation)
                .distinct()
                .collect(Collectors.toList());

        return new ReplacementOptions(options, replaceStart[0], at + 1);
    }

    @Override
    public String isComplete(String code) {
        return super.isComplete(code);
    }

    @Override
    public void onShutdown(boolean isRestarting) {
        this.shell.close();
    }
}
