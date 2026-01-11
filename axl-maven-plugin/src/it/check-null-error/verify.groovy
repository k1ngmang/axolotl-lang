def log = new File(basedir, 'build.log').text
def file = new File(basedir, 'src/main/axl/app/Main.axl').absolutePath
assert !log.contains('An exception has occurred in the compiler')
assert log.contains(file + ':5:') && log.contains('value `trimmed` may be null')
