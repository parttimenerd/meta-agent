package com.example.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Mojo that adds the meta-agent to the JVM arguments of the tests.
 */
@Mojo(name = "meta-agent", defaultPhase = LifecyclePhase.VALIDATE, threadSafe = true)
public class Agent extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "callbackClasses")
    private List<String> callbackClasses = new ArrayList<>();

    @Parameter(property = "server")
    private boolean server = false;

    @Parameter(property = "port")
    private int port = 7071;

    @Override
    public void execute() throws MojoFailureException {
        String agentPath = findAgentJar("meta-agent");
        if (agentPath == null) {
            throw new MojoFailureException("meta-agent not found in dependencies.");
        }

        String agentArg = "-javaagent:" + agentPath + "=" + callbackClasses.stream().map(c -> "cb=" + c).reduce((a, b) -> a + "," + b).orElse("") + (server ? ",server,port=" + port : "");
        getLog().info("Using agent: " + agentArg);
        Properties properties = project.getProperties();

        appendAgentToProperty(properties, "argLine", agentArg);
        appendAgentToProperty(properties, "surefire.argLine", agentArg);
        appendAgentToProperty(properties, "failsafe.argLine", agentArg);

    }

    private String findAgentJar(String artifactId) {
        Set<Artifact> artifacts = project.getDependencyArtifacts();
        for (Artifact artifact : artifacts) {
            if (artifact.getArtifactId().equals(artifactId)) {
                File file = artifact.getFile();
                if (file != null && file.exists()) {
                    return file.getAbsolutePath();
                }
            }
        }
        return null;
    }

    private void appendAgentToProperty(Properties properties, String key, String value) {
        String existingValue = properties.getProperty(key, "");
        if (!existingValue.contains(value)) {
            properties.setProperty(key, existingValue.isEmpty() ? value : existingValue + " " + value);
            getLog().info("Updated " + key + ": " + properties.getProperty(key));
        }
    }
}
