package org.axl.lsp;

import org.eclipse.lsp4j.services.LanguageClient;

/** Runs the Axolotl language server on standard input and output. */
public final class Main {

    private Main() {}

    public static void main(String[] args) throws Exception {
        AxlLanguageServer server = new AxlLanguageServer(System::exit);
        var launcher = AxlLanguageServer.launcher(server, System.in, System.out);
        LanguageClient client = launcher.getRemoteProxy();
        server.connect(client);
        launcher.startListening().get();
        // The client closed the input without `exit`: clean up as `exit` does, and end the JVM, which
        // lsp4j's idle threads would keep alive for a minute.
        server.exit();
    }
}
