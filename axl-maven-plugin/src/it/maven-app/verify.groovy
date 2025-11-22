def log = new File(basedir, 'build.log').text
assert log.contains('Tests run: 3, Failures: 0, Errors: 0, Skipped: 0')
assert new File(basedir, 'target/axl/generated-sources_axl.state.json').exists()
assert !new File(basedir, 'target/generated-sources/axl/.axl-state.json').exists()
assert new File(basedir, 'target/generated-test-sources/axl/app/Samples.java').exists()
def classFile = new File(basedir, 'target/classes/app/Temperatures.class').getText('ISO-8859-1')
assert classFile.contains('*S Axolotl')
