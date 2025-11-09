def log = new File(basedir, 'build.log').text
def file = new File(basedir, 'src/main/axl/app/Main.axl').absolutePath
assert log.contains(file + ':5:22: error: `greeting` of type `String` has no method `lenght()`')
