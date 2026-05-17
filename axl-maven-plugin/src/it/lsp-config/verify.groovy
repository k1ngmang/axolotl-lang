import groovy.json.JsonSlurper

def config = new JsonSlurper().parse(new File(basedir, 'target/axl/axl.json'))
def main = config.modules[0]
def test = config.modules[1]
def classes = new File(basedir, 'target/classes').absolutePath
def mainAxl = new File(basedir, 'src/main/axl').absolutePath
assert main.sourceRoots == [mainAxl]
assert main.classpath.any { it.contains('axl-runtime') }
assert !main.classpath.any { it.contains('junit') || it == classes }
assert test.sourceRoots == [new File(basedir, 'src/test/axl').absolutePath, mainAxl]
assert test.classpath.any { it.contains('junit') }
assert !test.classpath.any { it == classes }
