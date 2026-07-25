def report = new File(basedir, 'target/surefire-reports/TEST-app.NumbersTest.xml').text
assert report.contains('at app.Numbers.number(Numbers.java:')
assert !report.contains('Numbers.axl')
def classFile = new File(basedir, 'target/classes/app/Numbers.class').getText('ISO-8859-1')
assert !classFile.contains('*S Axolotl')
