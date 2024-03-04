Meta-Agent
==========
Check how agent transformed bytecode per class.

Just build the project with `mvn package -DskipTests` and run your Java program with the agent:

```shell
java -javaagent:target/meta.jar -jar your-program.jar

# or run a Mockito based sample test
mvn package -DskipTests
mvn test -DargLine="-javaagent:target/meta-agent.jar"
```

Then open your browser at `http://localhost:7071` (or your port) and
you will see a list of available commands.

Currently available commands are:
- `/` or `/help`

Contributions
-------------
If you have sample programs where this tool helped to see something interesting, please share.
Contributions, issues and PRs are welcome.

License
-------
MIT, Copyright 2024 SAP SE or an SAP affiliate company, Johannes Bechberger
and meta-agent agent contributors