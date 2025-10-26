'use strict';

const path = require('path');
const vscode = require('vscode');
const { LanguageClient } = require('vscode-languageclient/node');

// Error Prone runs inside the server's javac and needs the compiler internals.
const COMPILER_PACKAGES = ['api', 'code', 'comp', 'file', 'main', 'model', 'parser', 'processing', 'tree', 'util'];

let client;

function javaExecutable() {
  const configured = vscode.workspace.getConfiguration('axl').get('java.home') || process.env.JAVA_HOME;
  const name = process.platform === 'win32' ? 'java.exe' : 'java';
  return configured ? path.join(configured, 'bin', name) : name;
}

function activate(context) {
  const jvmArgs = COMPILER_PACKAGES.flatMap((p) => [
    `--add-exports=jdk.compiler/com.sun.tools.javac.${p}=ALL-UNNAMED`,
    `--add-opens=jdk.compiler/com.sun.tools.javac.${p}=ALL-UNNAMED`,
  ]);
  const classpath = path.join(context.extensionPath, 'server', 'lib', '*');
  const server = {
    command: javaExecutable(),
    args: [...jvmArgs, '-cp', classpath, 'org.axl.lsp.Main'],
  };
  client = new LanguageClient('axl', 'Axolotl', server, {
    documentSelector: [{ scheme: 'file', language: 'axl' }],
  });
  client.start();

  context.subscriptions.push(
    vscode.commands.registerCommand('axl.showGeneratedJava', async () => {
      const editor = vscode.window.activeTextEditor;
      if (!editor || editor.document.languageId !== 'axl') {
        return;
      }
      const java = await client.sendRequest('workspace/executeCommand', {
        command: 'axl.showGeneratedJava',
        arguments: [editor.document.uri.toString()],
      });
      const document = await vscode.workspace.openTextDocument({ language: 'java', content: java });
      await vscode.window.showTextDocument(document, vscode.ViewColumn.Beside, true);
    }),
  );
}

function deactivate() {
  return client ? client.stop() : undefined;
}

module.exports = { activate, deactivate };
