// No axl-smap and no JUnit extension: the classes themselves report .axl lines.
def report = new File(basedir, 'target/surefire-reports/TEST-app.NumbersTest.xml').text
assert report.contains('at app.Numbers.number(Numbers.axl:4)')
assert !report.contains('Numbers.java')
