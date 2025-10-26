def report = new File(basedir, 'target/surefire-reports/TEST-app.NumbersTest.xml').text
assert report.contains('at app.Numbers.number(Numbers.axl:4)')
