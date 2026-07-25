# Checking the debugger in IntelliJ IDEA

The JDI test in `axl-smap` proves that compiled classes carry the `Axolotl` stratum and that a
debugger can stop on an `.axl` line. This scenario checks the IDE side by hand.

1. Build and install the plugin: `./gradlew :axl-intellij:buildPlugin`, then
   *Settings | Plugins | Install Plugin from Disk* with `axl-intellij/build/distributions/*.zip`.
2. Publish the Axolotl artifacts locally: `./gradlew publishToMavenLocal`.
3. Open `samples/multi-module-gradle` in IntelliJ IDEA Community. Check that `core/src/main/axl`
   is a source root (blue folder) and that `build/generated/axl` is excluded.
4. Open `app/src/main/axl/app/Main.axl`. The run icon is shown next to `fn main()`.
5. Set a line breakpoint on `println(title(" Hello Axolotl "))`.
6. Click the run icon and choose *Debug 'Main'*. Gradle builds the project and the debugger stops
   on the breakpoint in `Main.axl`, not in the generated `Main.java`.
7. *Step Into* enters `core/src/main/axl/core/Titles.axl`, function `title`.
8. *Step Over* moves one `.axl` line at a time: the `try` in `shortTitle` (three Java lines) is one step.
9. The Variables view shows `raw`; locals created by the translator appear as `Axolotl internal $t1`.
10. Stop on a line after `val slug = try title(raw)` and check that `slug` shows its value.

11. Make `Main.axl` throw (`Integer.parseInt("x")`) and run it with *Run 'Main'*. The console
    prints `at app.Main.main(Main.axl:N)` and the link opens `Main.axl` at line N.

Expected result: every stop is shown in an `.axl` file at the line of the breakpoint or step.
