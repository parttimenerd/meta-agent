Meta-Agent Maven Plugin
=======================
A Maven plugin to add the [Meta-Agent](../meta-agent) to the tests of a project.

Usage
-----
Add the following to your `pom.xml`:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>me.bechberger</groupId>
            <artifactId>meta-agent-maven-plugin</artifactId>
            <version>0.0.3</version>
            <executions>
                <execution>
                    <phase>validate</phase>
                    <goals>
                        <goal>meta-agent</goal>
                    </goals>
                </execution>
            </executions>
            <configuration>
                <callbackClasses> <!-- to add an instrumentation callback handler -->
                    <callbackClass>me.bechberger.meta.LoggingInstrumentationHandler</callbackClass>
                </callbackClasses>
                <server>true</server> <!-- to start the meta-agent server -->
            </configuration>
        </plugin>
    </plugins>
</build>
```

And the following dependency:

```shell
<dependency>
    <groupId>me.bechberger</groupId>
    <artifactId>meta-agent</artifactId>
    <version>0.0.3</version>
    <scope>test</scope>
</dependency>
```

License
-------
MIT, Copyright 2024 SAP SE or an SAP affiliate company, Johannes Bechberger
and meta-agent agent contributors