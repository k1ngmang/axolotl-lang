def log = new File(basedir, 'build.log').text
def main = new File(basedir, 'src/main/axl/app/Main.axl')
// maven-compiler-plugin prints a file as its URI path on Windows: /D:/a/app/Main.axl.
def paths = [main.absolutePath, main.toURI().path]
assert paths.any { log.contains(it + ':[5,') } && log.contains('`home` may be null')
