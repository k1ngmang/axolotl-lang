def log = new File(basedir, 'build.log').text
assert log.contains('Tests run: 3, Failures: 0, Errors: 0, Skipped: 0')
assert new File(basedir, 'target/axl-state.json').exists()
assert new File(basedir, 'target/generated-test-sources/axl/app/Samples.java').exists()
