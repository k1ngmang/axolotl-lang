package org.axl.compiler.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class FormatterTest {

    private static String format(String source) {
        Formatter.Result result = Formatter.format("app/Main.axl", source);
        assertNull(result.internalError());
        assertEquals(List.of(), result.errors());
        return result.text();
    }

    /** Formats before, expects after, and expects after to stay as it is. */
    private static void formats(String before, String after) {
        assertEquals(after, format(before));
        assertEquals(after, format(after));
    }

    @Test
    void indentsBlocksByFourSpaces() {
        formats("""
                package app

                fn f(a: Int) -> Int {
                  if (a > 1) {
                \treturn a
                  }
                        return 0
                }
                """, """
                package app

                fn f(a: Int) -> Int {
                    if (a > 1) {
                        return a
                    }
                    return 0
                }
                """);
    }

    @Test
    void putsBracesAsTheDocumentationDoes() {
        formats("""
                package app

                fn f(a: Int){
                    if(a > 1){
                        println(a)
                    }else{
                        println(0)
                    }
                    while(a < 0){
                        println(a)
                    }
                }

                class C(val n: Int):Runnable{
                    fn run(){ }
                }
                """, """
                package app

                fn f(a: Int) {
                    if (a > 1) {
                        println(a)
                    } else {
                        println(0)
                    }
                    while (a < 0) {
                        println(a)
                    }
                }

                class C(val n: Int) : Runnable {
                    fn run() {}
                }
                """);
    }

    @Test
    void spacesBinaryOperatorsAndNotUnaryOnes() {
        formats("""
                package app

                fn f(a: Int, b: Boolean, s: String?) -> Int {
                    val x=a+2*a-a/2%3
                    val y=a==1&&b||a!=2
                    var z=a<=1
                    z = ! z
                    val w = s?:"none"
                    val l = s !!.length()
                    return - x
                }
                """, """
                package app

                fn f(a: Int, b: Boolean, s: String?) -> Int {
                    val x = a + 2 * a - a / 2 % 3
                    val y = a == 1 && b || a != 2
                    var z = a <= 1
                    z = !z
                    val w = s ?: "none"
                    val l = s!!.length()
                    return -x
                }
                """);
    }

    @Test
    void keepsSignsApartThatWouldJoin() {
        formats("""
                package app

                fn f(a: Int) {
                    println(- -a)
                    println(! !true)
                }
                """, """
                package app

                fn f(a: Int) {
                    println(- -a)
                    println(! !true)
                }
                """);
    }

    @Test
    void writesCountingLoopsAsTheDocumentationDoes() {
        formats("""
                package app

                fn f(d: Int, n: Int) {
                    for(k in -d .. d  step  2){
                        println(k)
                    }
                    for (i in n-1   downTo 0) {
                        println(i)
                    }
                    for (i in 0  until n) {
                        println(i)
                    }
                }
                """, """
                package app

                fn f(d: Int, n: Int) {
                    for (k in -d..d step 2) {
                        println(k)
                    }
                    for (i in n - 1 downTo 0) {
                        println(i)
                    }
                    for (i in 0 until n) {
                        println(i)
                    }
                }
                """);
    }

    @Test
    void spacesCommasAndColonsOfTypes() {
        formats("""
                package app

                import java.util.Map

                data class Pair<A,B>(val first:A,val second:B)

                fn largest<T:Comparable<T>>(xs:Map<String,T>)->T? = null

                interface Shape:Comparable<Shape>{
                    val name:String
                }
                """, """
                package app

                import java.util.Map

                data class Pair<A, B>(val first: A, val second: B)

                fn largest<T : Comparable<T>>(xs: Map<String, T>) -> T? = null

                interface Shape : Comparable<Shape> {
                    val name: String
                }
                """);
    }

    @Test
    void writesCallsAndGenericsWithoutSpaces() {
        formats("""
                package app

                import java.util.ArrayList
                import java.util.List

                fn f(xs : List < String >) -> Int {
                    val ys = ArrayList < String > (xs)
                    ys . add ( "a" )
                    return xs.size ()
                }
                """, """
                package app

                import java.util.ArrayList
                import java.util.List

                fn f(xs: List<String>) -> Int {
                    val ys = ArrayList<String>(xs)
                    ys.add("a")
                    return xs.size()
                }
                """);
    }

    @Test
    void spacesArrowsAndLambdas() {
        formats("""
                package app

                import java.util.List

                fn f(xs: List<Int>)->Int {
                    val n = xs |> filter{it>0} |> map{x->x*2} |> count
                    val r: Runnable = {->println(n)}
                    return when(n){
                        0L->0
                        else->1
                    }
                }
                """, """
                package app

                import java.util.List

                fn f(xs: List<Int>) -> Int {
                    val n = xs |> filter { it > 0 } |> map { x -> x * 2 } |> count
                    val r: Runnable = { -> println(n) }
                    return when (n) {
                        0L -> 0
                        else -> 1
                    }
                }
                """);
    }

    @Test
    void writesConstructorsAsFunctions() {
        formats("""
                package app

                class Opt(val name:String?,private val count:Int){
                    constructor (name:String?)=this(name,0)

                    @Deprecated
                    private   constructor ( ) = this ( null , 1 )
                    fn twice() -> Int = count * 2
                }
                """, """
                package app

                class Opt(val name: String?, private val count: Int) {
                    constructor(name: String?) = this(name, 0)

                    @Deprecated
                    private constructor() = this(null, 1)
                    fn twice() -> Int = count * 2
                }
                """);
    }

    @Test
    void writesEnumMethodsAfterTheConstants() {
        formats("""
                package app

                enum Predefined(val format:String):Supplier<String>,Named{
                  DEFAULT(","),
                    TDF("\\t") ;
                fn get()->String=format
                    fn width() -> Int {
                    return format.length()
                    }
                }
                """, """
                package app

                enum Predefined(val format: String) : Supplier<String>, Named {
                    DEFAULT(","),
                    TDF("\\t");
                    fn get() -> String = format
                    fn width() -> Int {
                        return format.length()
                    }
                }
                """);
    }

    @Test
    void writesTheConstructorOfAClassHeaderAfterTheName() {
        formats("""
                package app

                class Parser  private   constructor ( private val partial:Boolean ) {
                    constructor()=this(true)
                }

                class Builder @Deprecated( since = "2" )constructor()
                class Next @Deprecated
                private constructor(val a: Int)
                """, """
                package app

                class Parser private constructor(private val partial: Boolean) {
                    constructor() = this(true)
                }

                class Builder @Deprecated(since = "2") constructor()

                class Next @Deprecated
                    private constructor(val a: Int)
                """);
    }

    @Test
    void spacesTheClassOfAHandler() {
        formats("""
                package app

                import java.io.IOException
                import java.io.UncheckedIOException
                import java.nio.file.Files
                import java.nio.file.Path

                fn read(p: Path) -> String = attempt Files.readString(p) else e : IOException->throw UncheckedIOException(e)

                fn delete(ps: List<Path>) {
                    for (p in ps) {
                        attempt Files.delete(p) else e:IOException->continue
                    }
                    use (val r = Files.newBufferedReader(ps.get(0))) {
                        println(r.readLine())
                    } else e:IOException -> println(e)
                    use(hold( lock )){
                    println(1)
                    }
                }
                """, """
                package app

                import java.io.IOException
                import java.io.UncheckedIOException
                import java.nio.file.Files
                import java.nio.file.Path

                fn read(p: Path) -> String = attempt Files.readString(p) else e: IOException -> throw UncheckedIOException(e)

                fn delete(ps: List<Path>) {
                    for (p in ps) {
                        attempt Files.delete(p) else e: IOException -> continue
                    }
                    use (val r = Files.newBufferedReader(ps.get(0))) {
                        println(r.readLine())
                    } else e: IOException -> println(e)
                    use (hold(lock)) {
                        println(1)
                    }
                }
                """);
    }

    @Test
    void allowsOneBlankLineInARow() {
        formats("""


                package app



                fn f() {
                    println(1)


                    println(2)
                }
                """, """
                package app

                fn f() {
                    println(1)

                    println(2)
                }
                """);
    }

    @Test
    void separatesTopLevelDeclarationsByABlankLine() {
        formats("""
                package app
                import java.util.List
                val limit = 10
                fn f() {
                    println(limit)
                }
                fn g() {
                    println(limit)
                }
                // Short ones may stand together.
                fn one() = 1
                fn two() = 2
                data class P(val x: Int)
                """, """
                package app

                import java.util.List

                val limit = 10

                fn f() {
                    println(limit)
                }

                fn g() {
                    println(limit)
                }

                // Short ones may stand together.
                fn one() = 1
                fn two() = 2
                data class P(val x: Int)
                """);
    }

    @Test
    void sortsImportsAsOneGroup() {
        formats("""
                package app

                import java.util.Map
                import fn java.lang.Math.max  // the larger
                // Kept above its import.
                import app.Shapes.Circle

                import java.util.ArrayList
                import val app.Config.limit

                fn f() = max(limit, 1)
                """, """
                package app

                import val app.Config.limit
                // Kept above its import.
                import app.Shapes.Circle
                import fn java.lang.Math.max  // the larger
                import java.util.ArrayList
                import java.util.Map

                fn f() = max(limit, 1)
                """);
    }

    @Test
    void removesTrailingSpacesAndEndsWithANewline() {
        assertEquals("package app\n\nfn f() = 1 // one\n", format("package app   \n\nfn f() = 1 // one   "));
    }

    @Test
    void keepsLineEndsAndByteOrderMark() {
        assertEquals("﻿package app\r\n\r\nfn f() = 1\r\n", format("﻿package app\r\n\r\nfn f()=1\r\n"));
    }

    @Test
    void keepsCommentsStringsAndTheirSpaces() {
        formats("""
                package app

                /**
                   * A value.
                   */
                val a = "x  +  y"   // aligned
                val bb = 'c'        // aligned

                fn f() {
                        /* moved
                           with its line */
                    println(a/* inside */+ bb)
                }
                """, """
                package app

                /**
                   * A value.
                   */
                val a = "x  +  y"   // aligned
                val bb = 'c'        // aligned

                fn f() {
                    /* moved
                       with its line */
                    println(a/* inside */+ bb)
                }
                """);
    }

    @Test
    void keepsTheLineBreaksOfLongExpressions() {
        formats("""
                package app

                fn f(a: Int, b: Int) -> Int {
                  val sum = a +
                    b +
                          a
                  val call = Math.max(a,
                                      b)
                  return Integer.valueOf(sum)
                      .compareTo(call)
                }

                fn g(
                  a: Int,
                    b: Int
                ) = a
                """, """
                package app

                fn f(a: Int, b: Int) -> Int {
                    val sum = a +
                        b +
                            a
                    val call = Math.max(a,
                                        b)
                    return Integer.valueOf(sum)
                        .compareTo(call)
                }

                fn g(
                    a: Int,
                    b: Int
                ) = a
                """);
    }

    @Test
    void indentsABodyByTheLineItsDeclarationStarts() {
        formats("""
                package app

                fn f(a: Int,
                     b: Int) {
                    println(a)
                }
                """, """
                package app

                fn f(a: Int,
                     b: Int) {
                    println(a)
                }
                """);
    }

    @Test
    void indentsAnnotationsAndCommentsWithTheirDeclaration() {
        formats("""
                package app

                import org.junit.jupiter.api.Test

                class T {
                  @Test
                  fn a() {
                    // first
                    println(1)
                      // last
                  }
                }
                """, """
                package app

                import org.junit.jupiter.api.Test

                class T {
                    @Test
                    fn a() {
                        // first
                        println(1)
                        // last
                    }
                }
                """);
    }

    @Test
    void leavesFilesWithSyntaxErrorsUnchanged() {
        String broken = "package app\n\nfn f( {\n";
        Formatter.Result result = Formatter.format("app/Main.axl", broken);
        assertEquals(broken, result.text());
        assertFalse(result.errors().isEmpty());
        assertTrue(result.failed());
    }

    @Test
    void noticesAChangeOfMeaning() {
        String before = "package app\n\nfn f(a: Int) = a + 1\n";
        assertNull(Formatter.mismatch("app/Main.axl", before, "package app\n\nfn f(a: Int) =   a+1\n"));
        assertNotNull(Formatter.mismatch("app/Main.axl", before, "package app\n\nfn f(a: Int) = a - 1\n"));
        assertNotNull(Formatter.mismatch("app/Main.axl", before, "package app\n\nfn f(a: Int) = a\n+ 1\n"));
    }

    /** Every file of the conformance cases that parses: formatting keeps its meaning and changes nothing a second time. */
    @Test
    void conformanceSourcesKeepTheirMeaningAndFormatOnce() throws IOException {
        Path cases = Path.of("..", "tests", "conformance");
        List<Path> files;
        try (Stream<Path> walk = Files.walk(cases)) {
            files = walk.filter(p -> p.toString().endsWith(".axl") && p.toString().contains("src")).sorted().toList();
        }
        assertTrue(files.size() > 500, "found " + files.size());
        List<String> problems = new ArrayList<>();
        for (Path file : files) {
            String path = cases.relativize(file).toString().replace('\\', '/');
            String source = Files.readString(file);
            Formatter.Result once = Formatter.format(path, source);
            if (!once.errors().isEmpty()) {
                continue;
            }
            Formatter.Result twice = Formatter.format(path, once.text());
            if (once.internalError() != null) {
                problems.add(path + ": " + once.internalError());
            } else if (!twice.text().equals(once.text()) || twice.failed()) {
                problems.add(path + ": a second run changes it");
            } else if (!once.text().equals(source)) {
                String mismatch = Formatter.mismatch(path, source.replace("\r\n", "\n").replace('\r', '\n').replace("﻿", ""),
                        once.text().replace("\r\n", "\n").replace("﻿", ""));
                if (mismatch != null) {
                    problems.add(path + ": " + mismatch);
                }
            }
        }
        assertEquals(List.of(), problems);
    }
}
