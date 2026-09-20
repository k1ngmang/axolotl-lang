def log = new File(basedir, 'build.log').text
def legacy = new File(basedir, 'src/main/java/app/Legacy.java')
// maven-compiler-plugin prints a file as its URI path on Windows: /D:/a/app/Legacy.java.
def paths = [legacy.absolutePath, legacy.toURI().path]
assert log.count('`never` is never read') == 2
assert paths.any { log.contains(it + ':[5,13] [UnusedVariable]') }
assert log.contains(legacy.absolutePath + ':5:13: warning: [UnusedVariable]')
