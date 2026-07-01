"""The text of the guides: every sentence in English and Russian, with the parts that differ by system."""

RELEASES = "https://github.com/succubus-uwu/axolotl/releases"
DOCS = "https://github.com/succubus-uwu/axolotl/tree/v{v}/docs"

NAMES = {"windows": "Windows", "macos": "macOS", "linux": "Linux"}


class Paths:
    """How commands, paths and the classpath look on one system."""

    def __init__(self, system, v):
        windows = system == "windows"
        self.prompt = "> " if windows else "$ "
        self.sep = ";" if windows else ":"
        self.slash = "\\" if windows else "/"
        self.main = r"src\app\Main.axl" if windows else "src/app/Main.axl"
        self.broken = r"src\app\Broken.axl" if windows else "src/app/Broken.axl"
        repo = r"$env:USERPROFILE\.m2\repository" if windows else "~/.m2/repository"
        self.repo = repo
        self.runtime = (repo + rf"\org\axl\axl-runtime\{v}\axl-runtime-{v}.jar" if windows
                        else repo + f"/org/axl/axl-runtime/{v}/axl-runtime-{v}.jar")
        self.cont = " `" if windows else " \\"
        self.runtime_var = "$rt" if windows else "$RT"
        self.set_runtime = f'$rt = "{self.runtime}"' if windows else f"RT={self.runtime}"
        self.classes = r"out\classes" if windows else "out/classes"
        self.gen = r"out\gen" if windows else "out/gen"

    def args(self, line):
        """PowerShell splits `-Dname=a.b` at the dot unless it is quoted."""
        return " ".join(f'"{arg}"' for arg in line.split()) if self.sep == ";" else line

    def cmd(self, *lines):
        return "\n".join(self.prompt + line if line and not line.startswith(" ") else line.strip() for line in lines)


def guide(v, system, lang):
    def t(en, ru):
        return en if lang == "en" else ru

    p = Paths(system, v)
    name = NAMES[system]
    blocks = [("title", t(f"Axolotl {v} on {name}", f"Axolotl {v} для {name}"),
               t("Installing Axolotl and working with the axl command line compiler",
                 "Установка Axolotl и работа с компилятором командной строки axl"))]

    blocks += intro(t, v)
    blocks += requirements(t, system, v)
    blocks += install(t, system, v, p)
    blocks += check_install(t, v, p)
    blocks += first_program(t, p)
    blocks += commands(t, p)
    blocks += null_safety(t, system, p)
    blocks += compiled(t, system, v, p)
    blocks += stack_traces(t, p)
    blocks += build_tools(t, v, p)
    blocks += update_remove(t, system, v)
    blocks += troubleshooting(t, system)
    blocks += [("h1", t("More", "Дальше")),
               ("p", t(f"The documentation of the language, the Gradle and Maven plugins and the editors is at "
                       f"{DOCS.format(v=v)}. Releases and installers: {RELEASES}.",
                       f"Документация по языку, плагинам Gradle и Maven и редакторам: {DOCS.format(v=v)}. "
                       f"Релизы и установщики: {RELEASES}."))]

    meta = {
        "title": t(f"Axolotl {v} on {name}: installation and command line",
                   f"Axolotl {v} для {name}: установка и командная строка"),
        "subject": t("Installation and command line guide", "Руководство по установке и командной строке"),
        "footer": t(f"Axolotl {v} | {name} | installation and command line",
                    f"Axolotl {v} | {name} | установка и командная строка"),
    }
    return blocks, meta


def intro(t, v):
    return [
        ("h1", t("1. What you get", "1. Что устанавливается")),
        ("p", t("Axolotl is a JVM language that translates to readable Java 21. Its compiler checks that values "
                "which may be null are handled, using NullAway, and moves every error back to the line of the "
                "`.axl` file.",
                "Axolotl: язык для JVM, который переводится в читаемый Java 21. Компилятор через NullAway "
                "проверяет, что значения, которые могут быть null, обработаны, и показывает каждую ошибку в "
                "строке файла `.axl`.")),
        ("p", t("The installer does three things:", "Установщик делает три вещи:")),
        ("list", [
            t("installs the `axl` command together with its own Java 21 runtime, so `axl` needs no Java on the "
              "computer. The runtime has every module of Java SE, AWT and Swing included;",
              "ставит команду `axl` вместе с собственной средой Java 21, поэтому самому `axl` Java на компьютере "
              "не нужна. В среде есть все модули Java SE, включая AWT и Swing;"),
            t("puts `axl` on the `PATH`;", "добавляет `axl` в `PATH`;"),
            t("runs `axl setup`, which copies the Axolotl libraries and the Gradle and Maven plugins into the "
              "local Maven repository, so that Gradle and Maven projects find them.",
              "запускает `axl setup`, который копирует библиотеки Axolotl и плагины Gradle и Maven в локальный "
              "Maven-репозиторий, где их находят проекты Gradle и Maven."),
        ]),
    ]


def requirements(t, system, v):
    rows = {
        "windows": [
            t("64-bit Windows 10 or 11 on x64.", "64-битная Windows 10 или 11 на x64."),
            t("Administrator rights only to install for all users; an installation for yourself needs none.",
              "Права администратора нужны только для установки для всех пользователей; установка только для себя "
              "обходится без них."),
        ],
        "macos": [
            t("A Mac with Apple silicon (M1 or newer) and a current macOS.",
              "Mac на Apple silicon (M1 и новее) с актуальной macOS."),
            t(f"On an Intel Mac use `axl-{v}.zip` from the release page instead, with a JDK 21 installed: unzip it, "
              "put its `bin` directory on the `PATH` and run `axl setup`.",
              f"На Mac с процессором Intel используйте вместо этого `axl-{v}.zip` со страницы релиза и "
              "установленный JDK 21: распакуйте архив, добавьте его каталог `bin` в `PATH` и выполните `axl setup`."),
            t("An administrator password for the installation.", "Пароль администратора для установки."),
        ],
        "linux": [
            t("A 64-bit x86 Linux with glibc.", "64-битный Linux на x86 с glibc."),
            t("For the `.deb`: Debian 12 or Ubuntu 22.04 or newer, or a system based on them, and `sudo`.",
              "Для `.deb`: Debian 12 или Ubuntu 22.04 и новее либо основанный на них дистрибутив, и `sudo`."),
            t("For the tarball: any such Linux; no root is needed.", "Для архива tar.gz: любой такой Linux, root не нужен."),
        ],
    }[system]
    rows.append(t("Gradle and Maven, if you use them, run on a JDK 21 of their own: the runtime inside `axl` is "
                  "only for `axl`.",
                  "Gradle и Maven, если вы ими пользуетесь, работают на собственном JDK 21: среда внутри `axl` "
                  "служит только самому `axl`."))
    return [("h1", t("2. Requirements", "2. Требования")), ("list", rows)]


def install(t, system, v, p):
    page = f"{RELEASES}/tag/v{v}"
    blocks = [("h1", t("3. Installation", "3. Установка"))]
    if system == "windows":
        setup = f"axolotl-{v}-windows-x64-setup.exe"
        blocks += [
            ("steps", [
                t(f"Download `{setup}` from {page}.", f"Скачайте `{setup}` со страницы {page}."),
                t("Run it. The installer is not signed, so SmartScreen may show *Windows protected your PC*: "
                  "click *More info*, then *Run anyway*.",
                  "Запустите его. Установщик не подписан, поэтому SmartScreen может показать окно "
                  "*Windows protected your PC* (*Система Windows защитила ваш компьютер*): нажмите *More info* "
                  "(*Подробнее*), затем *Run anyway* (*Выполнить в любом случае*)."),
                t("Choose whether to install for all users, into `C:\\Program Files\\Axolotl` (needs administrator "
                  "rights), or only for yourself, into `%LOCALAPPDATA%\\Programs\\Axolotl`.",
                  "Выберите установку для всех пользователей, в `C:\\Program Files\\Axolotl` (нужны права "
                  "администратора), или только для себя, в `%LOCALAPPDATA%\\Programs\\Axolotl`."),
                t("Click through to the end. The installer adds the folder to the `PATH` (the system one for all "
                  "users, yours otherwise) and runs `axl setup` for you.",
                  "Пройдите шаги до конца. Установщик добавит каталог в `PATH` (системный при установке для всех, "
                  "иначе ваш личный) и выполнит `axl setup` от вашего имени."),
                t("Open a **new** terminal window (PowerShell, Windows Terminal or cmd). Windows that were open "
                  "during the installation do not see the new `PATH`.",
                  "Откройте **новое** окно терминала (PowerShell, Windows Terminal или cmd). Окна, открытые во "
                  "время установки, новый `PATH` не видят."),
            ]),
            ("p", t("Without questions, for example in a script (as administrator):",
                    "Без вопросов, например из скрипта (от имени администратора):")),
            ("code", p.cmd(f".\\{setup} /VERYSILENT /SUPPRESSMSGBOXES /NORESTART")),
        ]
    elif system == "macos":
        pkg = f"axolotl-{v}-macos-arm64.pkg"
        blocks += [
            ("steps", [
                t(f"Download `{pkg}` from {page}.", f"Скачайте `{pkg}` со страницы {page}."),
                t("Open it. The package is not signed, so macOS may refuse to open it. Then open *System Settings > "
                  "Privacy & Security*, scroll down and click *Open Anyway*, and open the package again.",
                  "Откройте его. Пакет не подписан, поэтому macOS может отказаться его открыть. Тогда откройте "
                  "*Системные настройки > Конфиденциальность и безопасность*, прокрутите вниз, нажмите "
                  "*Все равно открыть* и снова откройте пакет."),
                t("Follow the installer and enter your password when it asks.",
                  "Пройдите шаги установщика и введите пароль, когда он попросит."),
            ]),
            ("p", t("Instead of step 2 you can remove the quarantine mark in Terminal first:",
                    "Вместо шага 2 можно сначала снять отметку карантина в Терминале:")),
            ("code", p.cmd(f"xattr -d com.apple.quarantine ~/Downloads/{pkg}")),
            ("p", t("The package installs `/Applications/axl.app`, links `/usr/local/bin/axl` to it and runs "
                    "`axl setup` for the user who is logged in. `/usr/local/bin` is on the `PATH` of Terminal by "
                    "default. From the command line the same installation is:",
                    "Пакет ставит `/Applications/axl.app`, создаёт ссылку `/usr/local/bin/axl` и выполняет "
                    "`axl setup` для вошедшего в систему пользователя. `/usr/local/bin` по умолчанию есть в `PATH` "
                    "Терминала. Из командной строки та же установка выглядит так:")),
            ("code", p.cmd(f"sudo installer -pkg ~/Downloads/{pkg} -target /")),
        ]
    else:
        deb = f"axolotl-{v}-linux-amd64.deb"
        tarball = f"axolotl-{v}-linux-x64.tar.gz"
        blocks += [
            ("h2", t("Debian and Ubuntu: the .deb package", "Debian и Ubuntu: пакет .deb")),
            ("p", t(f"Download `{deb}` from {page} and install it with `apt`. The `./` matters: without it `apt` "
                    "looks for a package of that name in its repositories.",
                    f"Скачайте `{deb}` со страницы {page} и установите его через `apt`. Важно `./` в начале: без "
                    "него `apt` ищет пакет с таким именем в репозиториях.")),
            ("code", p.cmd("cd ~/Downloads", f"sudo apt install ./{deb}")),
            ("p", t("The package installs into `/opt/axolotl`, registers `/usr/bin/axl` with `update-alternatives`, "
                    "pulls in the X11, ALSA and font libraries the Java runtime uses, and runs `axl setup` for the "
                    "user who called `sudo`.",
                    "Пакет ставится в `/opt/axolotl`, регистрирует `/usr/bin/axl` через `update-alternatives`, "
                    "подтягивает библиотеки X11, ALSA и шрифтов, которые нужны среде Java, и выполняет "
                    "`axl setup` для пользователя, вызвавшего `sudo`.")),
            ("h2", t("Any Linux: the tarball", "Любой Linux: архив tar.gz")),
            ("p", t(f"Download `{tarball}`, unpack it and run `install.sh`. It needs no root and installs into "
                    "`~/.local/share/axolotl`, with `axl` linked from `~/.local/bin`:",
                    f"Скачайте `{tarball}`, распакуйте и запустите `install.sh`. Root не нужен: установка идёт в "
                    "`~/.local/share/axolotl`, а ссылка на `axl` появляется в `~/.local/bin`:")),
            ("code", p.cmd(f"tar xzf {tarball}", f"sh axolotl-{v}/install.sh")),
            ("p", t("`AXL_HOME` chooses another directory: `AXL_HOME=~/tools/axolotl sh axolotl-{v}/install.sh`. "
                    "If `~/.local/bin` is not on your `PATH` yet, the script says so; add this line to `~/.profile` "
                    "and log in again:".replace("{v}", v),
                    "Другой каталог задаёт `AXL_HOME`: `AXL_HOME=~/tools/axolotl sh axolotl-{v}/install.sh`. Если "
                    "`~/.local/bin` ещё нет в `PATH`, скрипт об этом скажет; добавьте эту строку в `~/.profile` и "
                    "войдите в систему заново:".replace("{v}", v))),
            ("code", 'export PATH="$HOME/.local/bin:$PATH"'),
            ("note", t("Programs that open windows or play sound through Java need the X11 and ALSA libraries of "
                       "your system; the `.deb` installs them, the tarball leaves that to you.",
                       "Программам, которые через Java открывают окна или играют звук, нужны библиотеки X11 и ALSA "
                       "системы; `.deb` ставит их сам, при установке из архива это остаётся за вами.")),
        ]
    return blocks


def check_install(t, v, p):
    return [
        ("h1", t("4. Checking the installation", "4. Проверка установки")),
        ("code", p.cmd("axl --version") + f"\naxl {v}"),
        ("p", t("`axl --help` lists the commands, and `axl help <command>` or `axl <command> --help` describes one.",
                "`axl --help` выводит список команд, а `axl help <команда>` или `axl <команда> --help` описывает "
                "одну из них.")),
    ]


def first_program(t, p):
    program = """package app

import java.util.List

fn greet(name: String?) -> String = "Hello, " + (name?.trim() ?: "stranger")

fn main() {
    val words = List.of(args)
    println(greet(if (words.isEmpty()) null else words.get(0)))
}"""
    return [
        ("h1", t("5. The first program", "5. Первая программа")),
        ("p", t("Sources live in a source directory, with one directory per package part, as in Java. Make a "
                "project directory with `src/app/Main.axl` in it:",
                "Исходники лежат в каталоге исходников, по каталогу на каждую часть имени пакета, как в Java. "
                "Создайте каталог проекта и в нём файл `src/app/Main.axl`:")),
        ("code", program),
        ("p", t("Run it from the project directory. Everything after the file name goes to the program as `args`:",
                "Запустите его из каталога проекта. Всё, что идёт после имени файла, программа получает в `args`:")),
        ("code", p.cmd(f"axl run {p.main}") + "\nHello, stranger\n" + p.cmd(f"axl run {p.main} Ann")
         + "\nHello, Ann"),
        ("p", t("`axl run` finds the source directory from the package of the file, translates and compiles every "
                "`.axl` and `.java` file in it, checks nulls and runs `fn main()`. Save the files in UTF-8.",
                "`axl run` находит каталог исходников по пакету файла, переводит и компилирует все файлы `.axl` и "
                "`.java` в нём, проверяет null и запускает `fn main()`. Файлы сохраняйте в UTF-8.")),
    ]


def commands(t, p):
    return [
        ("h1", t("6. The commands", "6. Команды")),
        ("table", [
            [t("Command", "Команда"), t("What it does", "Что делает")],
            ["`axl run <file> [args...]`", t("Compiles the source tree of the file and runs its `fn main()`.",
                                              "Компилирует дерево исходников файла и запускает его `fn main()`.")],
            ["`axl check <dirs...>`", t("Translates and compiles, keeps no output and prints the errors. Fast "
                                        "feedback, and what editors call.",
                                        "Переводит и компилирует, ничего не сохраняет и печатает ошибки. Быстрая "
                                        "проверка; её же вызывают редакторы.")],
            ["`axl build -d <dir> <src>`", t("Translates and compiles a source directory into class files.",
                                             "Переводит и компилирует каталог исходников в class-файлы.")],
            ["`axl retrace [file]`", t("Rewrites the `.java` lines of a stack trace to `.axl` lines.",
                                       "Заменяет в стеке вызовов строки `.java` на строки `.axl`.")],
            ["`axl setup`", t("Installs the libraries and plugins into the local Maven repository.",
                              "Устанавливает библиотеки и плагины в локальный Maven-репозиторий.")],
            ["`axl help [command]`", t("Describes the commands.", "Описывает команды.")],
        ], [52, 118]),
        ("h2", t("Options", "Параметры")),
        ("table", [
            [t("Option", "Параметр"), t("Commands", "Команды"), t("Meaning", "Значение")],
            ["`-cp`, `--classpath`", "run, check, build, retrace",
             t(f"Jars and class directories the code uses, separated by `{p.sep}`.",
               f"Jar-файлы и каталоги классов, которые использует код, через `{p.sep}`.")],
            ["`-d <dir>`", "build", t("Where the class files go.", "Куда писать class-файлы.")],
            ["`-s <dir>`", "build", t("Where the generated `.java` and `.axlmap` files go (default: the `-d` "
                                      "directory).",
                                      "Куда писать сгенерированные `.java` и `.axlmap` (по умолчанию: каталог из "
                                      "`-d`).")],
            ["`--strict-null`", "run, check, build",
             t("Treats values from Java code without nullness annotations as nullable.",
               "Считает значения из Java-кода без аннотаций null-безопасности nullable.")],
            ["`--no-null-checks`", "run, check, build",
             t("Skips Error Prone and NullAway: faster, but nulls are not checked.",
               "Отключает Error Prone и NullAway: быстрее, но null не проверяется.")],
            ["`--inline-elvis`", "run, check, build",
             t("Expands `?:` at statement level without a lambda.", "Раскрывает `?:` в операторах без лямбды.")],
            ["`--no-smap`", "build", t("Leaves out the line table for debuggers.",
                                       "Не добавляет таблицу строк для отладчиков.")],
            ["`-m`, `--map-dir`", "retrace", t("A directory with generated `.java` and `.axlmap` files; repeatable.",
                                               "Каталог со сгенерированными `.java` и `.axlmap`; можно повторять.")],
            ["`--repository`", "setup", t("The local Maven repository to install into.",
                                          "Локальный Maven-репозиторий, куда устанавливать.")],
            ["`-h`, `-V`", t("all", "все"), t("Help and version.", "Справка и версия.")],
        ], [36, 34, 100]),
    ]


def null_safety(t, system, p):
    broken = "package app\n\nfn shout(name: String?) -> String = name.toUpperCase()"
    message = (f"{p.broken}:3:42: error: value `name` may be null: use `?.`, `?:` or check "
               "`if (name != null)` [AXL102]")
    exit_code = "$LASTEXITCODE" if system == "windows" else "$?"
    return [
        ("h1", t("7. Checking code: axl check", "7. Проверка кода: axl check")),
        ("p", t(f"Add a file that forgets a null check, `{p.broken}`:",
                f"Добавьте файл, где забыта проверка на null, `{p.broken}`:")),
        ("code", broken),
        ("code", p.cmd("axl check src") + "\n" + message),
        ("p", t("Every message has the form `file:line:column: error|warning: text [code]`. The codes are listed "
                "in `docs/errors.md`: AXL0xx come from Axolotl's own checks, AXL1xx from javac, Error Prone and "
                "NullAway, reworded in Axolotl terms. Warnings do not stop a build.",
                "Каждое сообщение имеет вид `файл:строка:столбец: error|warning: текст [код]`. Коды перечислены в "
                "`docs/errors.md`: AXL0xx выдают собственные проверки Axolotl, AXL1xx приходят от javac, "
                "Error Prone и NullAway и переформулированы в терминах Axolotl. Предупреждения сборку не "
                "останавливают.")),
        ("p", t(f"`axl check`, `axl build` and `axl run` exit with code 1 when there are errors and 0 otherwise, "
                f"so scripts and CI can test `{exit_code}`. `axl check` takes several source directories, such as "
                "`axl check src/main src/test`.",
                f"`axl check`, `axl build` и `axl run` завершаются с кодом 1 при ошибках и 0 без них, поэтому "
                f"скрипты и CI могут проверять `{exit_code}`. `axl check` принимает несколько каталогов исходников, "
                "например `axl check src/main src/test`.")),
        ("p", t("Fix it with `name?.toUpperCase() ?: \"\"` and `axl check src` prints nothing.",
                "Исправьте на `name?.toUpperCase() ?: \"\"`, и `axl check src` ничего не выведет.")),
    ]


def compiled(t, system, v, p):
    run = f'java -cp "{p.classes}{p.sep}{p.runtime_var}" app.Main Bob'
    return [
        ("h1", t("8. Building class files: axl build", "8. Сборка class-файлов: axl build")),
        ("code", p.cmd(f"axl build -d {p.classes} -s {p.gen} src")),
        ("p", t(f"`{p.classes}` gets the class files, `{p.gen}` the generated Java and the `.axlmap` line maps. "
                "The generated Java is plain Java 21 you can read. Code that uses other libraries passes them "
                f"with `-cp`, separated by `{p.sep}`.",
                f"В `{p.classes}` попадают class-файлы, в `{p.gen}` сгенерированный Java и карты строк `.axlmap`. "
                "Сгенерированный Java это обычный читаемый Java 21. Если код использует другие библиотеки, "
                f"передайте их через `-cp`, разделяя `{p.sep}`.")),
        ("p", t(f"The classes run on any Java 21 or newer with the Axolotl runtime library on the classpath. "
                "`axl setup` put it into the local Maven repository. `axl` does not provide a `java` command, so "
                "this needs a Java of your own:",
                "Классы запускаются на любой Java 21 и новее, если на classpath есть библиотека времени выполнения "
                "Axolotl. `axl setup` положил её в локальный Maven-репозиторий. Команды `java` в `axl` нет, "
                "поэтому для этого нужна своя Java:")),
        ("code", p.cmd(p.set_runtime, run) + "\nHello, Bob"),
        ("p", t("For anything larger than a few files a Gradle or Maven build is simpler (section 10).",
                "Для чего-то большего, чем несколько файлов, проще сборка Gradle или Maven (раздел 10).")),
    ]


def stack_traces(t, p):
    trace_java = ("Exception in thread \"main\" java.lang.IndexOutOfBoundsException: Index: 5 Size: 1\n"
                  "    ...\n    at app.Main.first(Main.java:9)\n    at app.Main.main(Main.java:12)")
    trace_axl = "    at app.Main.first(Main.axl:5)\n    at app.Main.main(Main.axl:8)"
    pipe = f'java -cp "{p.classes}{p.sep}{p.runtime_var}" app.Main 2>&1 | axl retrace -cp {p.classes}'
    return [
        ("h1", t("9. Stack traces: axl retrace", "9. Стек вызовов: axl retrace")),
        ("p", t("`axl run` already prints `.axl` lines. A program started with `java` prints the lines of the "
                "generated Java:",
                "`axl run` сразу печатает строки `.axl`. Программа, запущенная через `java`, печатает строки "
                "сгенерированного Java:")),
        ("code", trace_java),
        ("p", t("`axl retrace` reads such a trace from a file or from standard input and points it at the `.axl` "
                "lines, using the line table in the classes (`-cp`) or the `.axlmap` files (`-m`):",
                "`axl retrace` читает такой стек из файла или со стандартного ввода и переводит его на строки "
                "`.axl` по таблице строк в классах (`-cp`) или по файлам `.axlmap` (`-m`):")),
        ("code", p.cmd(f"axl retrace -cp {p.classes} trace.txt") + "\n" + trace_axl),
        ("code", p.cmd(pipe)),
    ]


def build_tools(t, v, p):
    gradle = f"""// settings.gradle.kts
pluginManagement {{ repositories {{ mavenLocal(); gradlePluginPortal() }} }}
dependencyResolutionManagement {{ repositories {{ mavenLocal(); mavenCentral() }} }}

// build.gradle.kts
plugins {{
    application
    id("org.axl.lang") version "{v}"
}}
application {{ mainClass = "app.Main" }}"""
    return [
        ("h1", t("10. Gradle and Maven projects", "10. Проекты Gradle и Maven")),
        ("p", t("After `axl setup` both build tools find Axolotl in the local Maven repository. They still download "
                "Error Prone, NullAway and the other dependencies from Maven Central, and need a JDK 21.",
                "После `axl setup` обе системы сборки находят Axolotl в локальном Maven-репозитории. Error Prone, "
                "NullAway и прочие зависимости они по-прежнему скачивают из Maven Central, и им нужен JDK 21.")),
        ("h2", "Gradle"),
        ("code", gradle),
        ("p", t("Sources go to `src/main/axl` and `src/test/axl`; `gradle run` builds and runs the program.",
                "Исходники кладутся в `src/main/axl` и `src/test/axl`; `gradle run` собирает и запускает программу.")),
        ("h2", "Maven"),
        ("p", t("A new project from the archetype, ready to build with `mvn package`:",
                "Новый проект из архетипа, готовый к сборке через `mvn package`:")),
        ("code", p.cmd(f"mvn archetype:generate{p.cont}") + "\n" + "\n".join(
            "    " + p.args(line) + (p.cont if i < 2 else "") for i, line in enumerate([
                "-DarchetypeGroupId=org.axl -DarchetypeArtifactId=axl-archetype-quickstart",
                f"-DarchetypeVersion={v}",
                "-DgroupId=org.example -DartifactId=demo -Dpackage=org.example.demo"]))),
    ]


def update_remove(t, system, v):
    update = {
        "windows": t("Run the setup program of the new version; it replaces the old files and runs `axl setup`.",
                     "Запустите установщик новой версии: он заменит старые файлы и выполнит `axl setup`."),
        "macos": t("Open the package of the new version; it replaces `/Applications/axl.app` and runs `axl setup`.",
                   "Откройте пакет новой версии: он заменит `/Applications/axl.app` и выполнит `axl setup`."),
        "linux": t("Install the new `.deb` with `sudo apt install ./...deb`, or run the `install.sh` of the new "
                   "tarball, which replaces the earlier installation.",
                   "Установите новый `.deb` через `sudo apt install ./...deb` или запустите `install.sh` из нового "
                   "архива: он заменит прежнюю установку."),
    }[system]
    remove = {
        "windows": [t("*Settings > Apps > Installed apps*, find *Axolotl* and choose *Uninstall*. The uninstaller "
                      "also takes the folder off the `PATH`.",
                      "*Параметры > Приложения > Установленные приложения*, найдите *Axolotl* и выберите "
                      "*Удалить*. Деинсталлятор убирает каталог и из `PATH`.")],
        "macos": [t("In Terminal:", "В Терминале:")],
        "linux": [t("The `.deb`: `sudo apt remove axolotl`.", "Пакет `.deb`: `sudo apt remove axolotl`."),
                  t("The tarball: delete `~/.local/share/axolotl` (or your `AXL_HOME`) and `~/.local/bin/axl`.",
                    "Архив: удалите `~/.local/share/axolotl` (или ваш `AXL_HOME`) и `~/.local/bin/axl`.")],
    }[system]
    blocks = [("h1", t("11. Updating and uninstalling", "11. Обновление и удаление")),
              ("p", update),
              ("list", remove)]
    if system == "macos":
        blocks.append(("code", "$ sudo rm -rf /Applications/axl.app /usr/local/bin/axl\n"
                               "$ sudo pkgutil --forget org.axl.cli"))
    blocks.append(("p", t("The libraries `axl setup` installed stay in the local Maven repository under `org/axl`; "
                          "delete that directory too if no project needs them.",
                          "Библиотеки, установленные `axl setup`, остаются в локальном Maven-репозитории в каталоге "
                          "`org/axl`; удалите и его, если они не нужны ни одному проекту.")))
    return blocks


def troubleshooting(t, system):
    common = [
        [t("Maven or Gradle cannot find `org.axl.lang` or `axl-maven-plugin`.",
           "Maven или Gradle не находит `org.axl.lang` или `axl-maven-plugin`."),
         t("Run `axl setup` as the user who builds; check that the build lists `mavenLocal()`. If you set "
           "`-Dmaven.repo.local` in `MAVEN_OPTS`, run `axl setup` again from that shell, or pass "
           "`axl setup --repository <dir>`.",
           "Выполните `axl setup` от имени пользователя, который собирает проект; проверьте, что в сборке есть "
           "`mavenLocal()`. Если в `MAVEN_OPTS` задан `-Dmaven.repo.local`, повторите `axl setup` из этой "
           "оболочки или укажите `axl setup --repository <каталог>`.")],
        [t("`java` is not found, or is older than 21, when running built classes or Gradle.",
           "`java` не найдена или старее 21 при запуске собранных классов или Gradle."),
         t("Install a JDK 21; `axl` keeps its own runtime to itself.",
           "Установите JDK 21; собственная среда `axl` служит только ему.")],
        [t("A compile takes long the first time.", "Первая компиляция идёт долго."),
         t("`axl` starts a JVM and loads javac and NullAway on every call; a few seconds are normal. "
           "`--no-null-checks` skips NullAway for quick experiments.",
           "`axl` при каждом вызове запускает JVM и загружает javac и NullAway; несколько секунд это нормально. "
           "Для быстрых экспериментов `--no-null-checks` пропускает NullAway.")],
    ]
    specific = {
        "windows": [
            [t("*'axl' is not recognized as an internal or external command*.",
               "*'axl' не является внутренней или внешней командой*."),
             t("Open a new terminal. If that does not help, sign out and in again so that Windows reloads the "
               "`PATH`.",
               "Откройте новый терминал. Если не помогло, выйдите из учётной записи и войдите снова, чтобы Windows "
               "перечитала `PATH`.")],
            [t("SmartScreen blocks the installer.", "SmartScreen блокирует установщик."),
             t("*More info*, then *Run anyway*: the installer is not signed.",
               "*Подробнее*, затем *Выполнить в любом случае*: установщик не подписан.")],
            [t("In PowerShell, a classpath with `;` breaks the command.",
               "В PowerShell classpath с `;` ломает команду."),
             t("Put the whole classpath in double quotes, as in the examples.",
               "Берите весь classpath в двойные кавычки, как в примерах.")],
        ],
        "macos": [
            [t("*zsh: command not found: axl*.", "*zsh: command not found: axl*."),
             t("Check that `/usr/local/bin/axl` exists and that `/usr/local/bin` is on the `PATH`: add "
               "`export PATH=\"/usr/local/bin:$PATH\"` to `~/.zshrc`.",
               "Проверьте, что `/usr/local/bin/axl` существует и `/usr/local/bin` есть в `PATH`: добавьте "
               "`export PATH=\"/usr/local/bin:$PATH\"` в `~/.zshrc`.")],
            [t("macOS refuses to open the package.", "macOS отказывается открыть пакет."),
             t("*System Settings > Privacy & Security > Open Anyway*, or `xattr -d com.apple.quarantine` on the "
               "package (section 3).",
               "*Системные настройки > Конфиденциальность и безопасность > Все равно открыть* или "
               "`xattr -d com.apple.quarantine` для пакета (раздел 3).")],
            [t("Another user of the Mac needs the Maven libraries.", "Maven-библиотеки нужны другому пользователю Mac."),
             t("That user runs `axl setup` once.", "Этот пользователь один раз выполняет `axl setup`.")],
        ],
        "linux": [
            [t("*E: Unable to locate package* from `apt`.", "*E: Unable to locate package* от `apt`."),
             t("Write the path with `./` (or a full path): `sudo apt install ./axolotl-...deb`.",
               "Укажите путь с `./` (или полный путь): `sudo apt install ./axolotl-...deb`.")],
            [t("`apt` cannot satisfy the dependencies on an older release.",
               "`apt` не может удовлетворить зависимости на старом выпуске."),
             t("Use the tarball, which depends on nothing.", "Используйте архив tar.gz: у него нет зависимостей.")],
            [t("*axl: command not found* after the tarball.", "*axl: command not found* после установки из архива."),
             t("Add `~/.local/bin` to the `PATH` (section 3) and open a new terminal.",
               "Добавьте `~/.local/bin` в `PATH` (раздел 3) и откройте новый терминал.")],
        ],
    }[system]
    return [("h1", t("12. Troubleshooting", "12. Решение проблем")),
            ("table", [[t("Problem", "Проблема"), t("What to do", "Что делать")]] + specific + common, [62, 108])]
