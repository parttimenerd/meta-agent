Meta-Agent
==========

Who instruments the instrumenter? This project is a Java agent that instruments Java agents,
or specifically, it instruments the ClassFileTransformers of other agents to observe how they transform
the bytecode of classes.

This is especially useful to check what libraries like [Mockito](https://site.mockito.org/) do to your classes at runtime.

To run it, build the project with `mvn package -DskipTests` and run your Java program with the agent:

```shell
java -javaagent:target/meta-agent.jar -jar your-program.jar

# or run a Mockito based sample test
mvn package -DskipTests
mvn test -DargLine="-javaagent:target/meta-agent.jar"
```

The executed [MockitoTest](src/test/java/me/bechberger/meta/MockitoTest.java) looks as follows:

```java
@ExtendWith(MockitoExtension.class)
public class MockitoTest {

  @Mock
  List<String> mockedList;

  @Test
  public void whenNotUseMockAnnotation_thenCorrect() throws InterruptedException {
    mockedList.add("one");
    Mockito.verify(mockedList).add("one");
    assertEquals(0, mockedList.size());

    Mockito.when(mockedList.size()).thenReturn(100);
    assertEquals(100, mockedList.size());

    Thread.sleep(10000000L);
  }
}
```

Opening [localhost](http://localhost:7071) will show you a list of available commands, most importantly
- [/help](http://localhost:7071) to show the help, available comands and decompilation and output options
- [/instrumentators](http://localhost:7071/instrumentators) to list all instrumentators (ClassFileTransformers) that have been used
- [/full-diff/instrumentator?pattern=.*](http://localhost:7071/full-diff/instrumentator?pattern=.*)
  to show the full diff for all instrumentators
- [/classes](http://localhost:7071/classes) to list all classes that have been transformed
- [/full-diff/class?pattern=.*](http://localhost:7071/full-diff/class?pattern=.*)
  to show the full diff for all classes and all instrumentators
- [/all/decompile?pattern=<pattern>](http://localhost:7071/all/decompile?pattern=<pattern>)
  to decompile the classes matching the pattern

In our example, we can see via [/instrumentators](http://localhost:7071/instrumentators) that Mockito uses
the `org.mockito.internal.creation.bytebuddy.InlineByteBuddyMockMaker` to transform classes.
Using [/full-diff/instrumentator/.*](http://localhost:7071/full-diff/instrumentator/.*), we can see the diff of all
transformations that this instrumentator has done:

![Screenshot of http://localhost:7071/full-diff/instrumentator/.*](img/instrumentators.png)

Yet we also see via [/classes](http://localhost:7071/classes) that Mockito only transforms the `java.util.List` 
interface and all its parents:

![Screenshot of http://localhost:7071/classes](img/classes.png)

How this works
--------------

The agent wraps all ClassFileTransformers with a custom transformer that records the diff of the bytecode.
It then uses [vineflower](http://vineflower.org/) to decompile the bytecode and 
[diff](https://www.gnu.org/software/diffutils/)
to compute the diff between the original and the transformed bytecode.

The front-end is implemented using the built-in HttpServer as a simple web server started by the agent.

This is essentially a more capable version of the [classviewer-agent](https://github.com/parttimenerd/classviewer-agent).-

Contributions
-------------
If you have sample programs where this tool helped to see something interesting, please share.
Contributions, issues and PRs are welcome.

License
-------
MIT, Copyright 2024 SAP SE or an SAP affiliate company, Johannes Bechberger
and meta-agent agent contributors
