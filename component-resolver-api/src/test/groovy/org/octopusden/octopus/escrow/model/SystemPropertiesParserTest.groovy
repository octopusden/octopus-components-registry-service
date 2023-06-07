package org.octopusden.octopus.escrow.model

class SystemPropertiesParserTest extends GroovyTestCase {
    void testParse() {
        def properties = " -Dtest.property1=1 -Ptest2=2 -Pempty.value= -Dsome=-Dlooks=-Pcool "
        def map = ["test.property1": "1", "test2": "2", "empty.value": "", "some": "-Dlooks=-Pcool"]
        assertEquals map, SystemPropertiesParser.parse(properties)
    }

    void testParse2() {
        def properties = "-DJAVA_1_5_HOME=../../../tools/BUILD_ENV/JDK/1.5"
        def map = ["JAVA_1_5_HOME": "../../../tools/BUILD_ENV/JDK/1.5"]
        assertEquals map, SystemPropertiesParser.parse(properties)
    }

    void testParsePath() {
        def properties = "-PpathMsbuild=C:\\\\Windows\\\\Microsoft.NET\\\\Framework\\\\v4.0.30319"
        def map = ["pathMsbuild": "C:\\\\Windows\\\\Microsoft.NET\\\\Framework\\\\v4.0.30319"]
        assertEquals map, SystemPropertiesParser.parse(properties)
    }
}
