package org.axl.lsp;

import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;

/** Runs the Axolotl language server on standard input and output. */
public final class Main {

    private Main() {}

    public static void main(String[] args) throws Exception {
        AxlLanguageServer server = new AxlLanguageServer();
        var launcher = LSPLauncher.createServerLauncher(server, System.in, System.out);
        LanguageClient client = launcher.getRemoteProxy();
        server.connect(client);
        launcher.startListening().get();
    }
}
