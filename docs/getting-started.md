# Getting started

This page builds a small Gradle project with Axolotl and Java code side by side. It takes about ten
minutes. You need JDK 21.

## 1. Install Axolotl

Axolotl is not published to a public repository yet; it goes into your local Maven repository
(`~/.m2/repository`) instead. The [installers](install.md#installers) do that for you. Without them, build it once from source, which
needs Git too:

```
git clone https://github.com/sora-vuv/axolotl-lang.git
cd axolotl
./gradlew publishToMavenLocal
```

## 2. Create a project

Make an empty directory with three files. Copy `gradlew`, `gradlew.bat` and the `gradle` directory
from the Axolotl checkout to get the Gradle wrapper, or use Gradle 8.5 or newer if you have it.

`settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
    }
}

rootProject.name = "hello-axl"
```

`build.gradle.kts`:

```kotlin
plugins {
    application
    id("org.axl.lang") version "0.3.0"
}

application {
    mainClass = "app.Main"
}
```

`src/main/axl/app/Main.axl`:

```axl
package app

fn greet(name: String?) -> String = "Hello, " + (name?.trim() ?: "stranger")

fn main() {
    println(greet(System.getProperty("user.name")))
}
```

Run it:

```
./gradlew run
```

The plugin translated `Main.axl` into `build/generated/axl/main/app/Main.java`, compiled it with
NullAway and ran it. Open the generated file: it is plain Java you could have written by hand.

## 3. Let the compiler catch a null

Change `greet` so that it forgets the null check:

```axl
fn greet(name: String?) -> String = "Hello, " + name.trim()
```

`./gradlew run` now fails at the `.axl` line (Gradle prints the absolute path of the file):

```
/home/me/hello-axl/src/main/axl/app/Main.axl:3: error: value `name` may be null: use `?.`, `?:` or check `if (name != null)`
fn greet(name: String?) -> String = "Hello, " + name.trim()
                                                     ^
1 error
```

Undo the change before going on.

## 4. Handle errors with `Result`

Add a file `src/main/axl/app/Ports.axl`:

```axl
package app

fn parsePort(text: String) -> Result<Int, String> {
    val port = try attempt Integer.parseInt(text.trim()) else "not a number: " + text
    if (port < 1 || port > 65535) {
        return Err("out of range: " + port)
    }
    return Ok(port)
}

fn describe(text: String) -> String {
    val result = parsePort(text)
    return result.toString()
}
```

`attempt` turns the exception of `parseInt` into an `Err`; `try` unwraps an `Ok` or returns the
`Err` from `parsePort`. Functions of other files are imported with `import fn`; update `Main.axl`:

```axl
package app

import fn app.Ports.describe

fn greet(name: String?) -> String = "Hello, " + (name?.trim() ?: "stranger")

fn main() {
    println(greet(System.getProperty("user.name")))
    println(describe("8080"))
    println(describe("eighty"))
}
```

```
Ok[value=8080]
Err[error=not a number: eighty]
```

## 5. Call it from Java

Java code in `src/main/java` sees every `.axl` file as a class. Add
`src/main/java/app/PortCheck.java`:

```java
package app;

import axl.lang.Err;
import axl.lang.Ok;

public final class PortCheck {
    private PortCheck() {}

    public static boolean isValid(String text) {
        return switch (Ports.parsePort(text)) {
            case Ok<Integer, String> ok -> true;
            case Err<Integer, String> err -> false;
        };
    }
}
```

Axolotl calls Java the same way: `println(PortCheck.isValid("80"))` works in `Main.axl`.

## 6. Test it

Add JUnit to `build.gradle.kts`:

```kotlin
dependencies {
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
```

and a test in `src/test/axl/app/PortsTest.axl`. A class whose methods carry `@Test` is a JUnit test
class, and the assertions are imported as functions:

```axl
package app

import fn org.junit.jupiter.api.Assertions.assertEquals
import fn org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PortsTest {
    @Test
    fn parsesPorts() {
        assertEquals(Ok(8080), Ports.parsePort(" 8080 "))
        assertTrue(Ports.parsePort("x") is Err)
    }
}
```

`./gradlew test` runs it. When a test fails inside Axolotl code, the stack trace shows `.axl`
lines. A Java method that declares a checked exception is called in a test as
`attempt client.send(request, handler) else e -> throw IllegalStateException(e)`, which gives its
value and fails the test if it throws.

## Next steps

- Set up your editor: [installation](install.md).
- Read [the language](language.md) and the [expansion table](expansion.md).
- Bring existing code over with [migrating from Java](migration.md).
