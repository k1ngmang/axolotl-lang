def log = new File(basedir, 'build.log').text
// Error Prone still checks the generated code, once in compile and once in axl:check.
assert log.count('`never` is never read') == 2
assert !log.contains('Legacy.java')
assert !log.contains('Labels.java')
