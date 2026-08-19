def log = new File(basedir, 'build.log').text
def file = new File(basedir, 'src/main/axl/app/Main.axl').absolutePath
assert log.contains(file + ':6:12: error: `return try e` makes no sense')
assert log.contains('[AXL005]')
