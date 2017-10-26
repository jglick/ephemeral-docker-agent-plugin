/*
 * The MIT License
 *
 * Copyright 2017 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.ephemeral_docker_agent;

import com.google.common.collect.ImmutableSet;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Proc;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.OfflineCause;
import java.util.Set;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.support.actions.WorkspaceActionImpl;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

public class DockerNodeStep extends Step {
    
    private String image = DescriptorImpl.DEFAULT_IMAGE;

    @DataBoundConstructor public DockerNodeStep() {}

    public String getImage() {
        return image;
    }

    @Override public StepExecution start(StepContext context) throws Exception {
        return new Execution(context, image);
    }

    @DataBoundSetter public void setImage(String image) {
        if (!isValidImageWithTag(image)) {
            throw new IllegalArgumentException();
        }
        this.image = image;
    }

    // https://docs.docker.com/engine/reference/commandline/tag/#extended-description
    static final String HOST_COMPONENT = "[A-Za-z0-9]|[A-Za-z0-9][A-Za-z0-9-]*[A-Za-z0-9]";
    static final String HOST_NAME = HOST_COMPONENT + "([.]" + HOST_COMPONENT + ")*";
    static final String REGISTRY_PREFIX = HOST_NAME + "(:[0-9]+)?/";
    static final String SEPARATOR = "[.]|_|__|-+";
    static final String NAME_COMPONENT = "[a-z0-9]+(" + SEPARATOR + "[a-z0-9]+)*";
    static final String IMAGE_NAME = "(" + REGISTRY_PREFIX + ")?" + NAME_COMPONENT + "(/" + NAME_COMPONENT + ")*";
    static final String TAG_NAME = "[a-zA-Z0-9_][a-zA-Z0-9_.-]*";
    static final String IMAGE_WITH_TAG = IMAGE_NAME + "(?::" + TAG_NAME + ")?";
    static boolean isValidImageWithTag(String n) {
        return n.matches(IMAGE_WITH_TAG);
    }

    private static class Execution extends StepExecution {

        private final String image;

        Execution(StepContext context, String image) {
            super(context);
            this.image = image;
        }

        @Override public boolean start() throws Exception {
            TaskListener listener = getContext().get(TaskListener.class);
            Launcher launcher = getContext().get(Launcher.class);
            if (launcher == null) { // outside node {}
                launcher = new Launcher.LocalLauncher(listener);
            }
            // TODO launch asynchronously, not in CPS VM thread
            // TODO allow safe customizations (if using LocalLauncher, must not permit arbitrary Docker options)
            FlowNode flowNode = getContext().get(FlowNode.class);
            String name = flowNode.getUrl().replaceFirst("/$", "").replaceAll("[^a-zA-Z0-9]", "_");
            EphemeralSlave s = new EphemeralSlave(name, /* TODO allow customization */ "/home/jenkins/agent", new EphemeralLauncher());
            Jenkins.getInstance().addNode(s);
            EphemeralComputer c = (EphemeralComputer) s.toComputer();
            if (c == null) {
                throw new IllegalStateException();
            }
            Proc proc = launcher.launch().
                writeStdin().readStdout().stderr(listener.getLogger()).
                cmds("docker", "run", "-i", "--name", name, "--rm", "--entrypoint", "java", image, "-jar", "/usr/share/jenkins/slave.jar").
                start();
            c.setChannel(proc.getStdout(), proc.getStdin(), listener, null);
            EnvVars env = c.getEnvironment();
            env.overrideExpandingAll(c.buildEnvironment(listener));
            // TODO augment env with various standard stuff; cf. ExecutorStepExecution
            FilePath ws = s.createPath(/* TODO allow customization */ "/home/jenkins/ws");
            flowNode.addAction(new WorkspaceActionImpl(ws, flowNode));
            getContext().newBodyInvoker().withCallback(new Callback(s)).withContexts(c, env, ws).start();
            return false;
        }

        @Override public void onResume() {
            // TODO would need a rewrite to use `--detach --entrypoint sleep infinity` and use `docker exec java -jar slave.jar` or something
            getContext().onFailure(new Exception("Resume after a restart not yet supported for dockerNode"));
        }

    }

    private static class Callback extends BodyExecutionCallback.TailCall {

        private final String nodeName;

        Callback(Node node) {
            nodeName = node.getNodeName();
        }

        @Override protected void finished(StepContext context) throws Exception {
            Node node = Jenkins.getInstance().getNode(nodeName);
            if (node != null) {
                Computer c = node.toComputer();
                if (c != null) {
                    c.disconnect(new OfflineCause() {
                        @Override public String toString() {
                            return "dockerNode completed";
                        }
                    });
                    // TODO also proc.kill() in a future listener after disconnect finishes
                }
                Jenkins.getInstance().removeNode(node);
            }
            // TODO sometimes produces noisy warning: hudson.remoting.Request$2#run: Failed to send back a reply to the request hudson.remoting.Request$2@…
        }

    }

    @Extension public static class DescriptorImpl extends StepDescriptor {

        public static final String DEFAULT_IMAGE = "jenkinsci/slave";

        @Override public boolean takesImplicitBlockArgument() {
            return true;
        }

        @SuppressWarnings("unchecked")
        @Override public Set<? extends Class<?>> getProvidedContext() {
            // TODO can/should we provide Executor? We cannot access Executor.start(WorkUnit) from outside the package. cf. isAcceptingTasks, withContexts
            return ImmutableSet.of(Computer.class, FilePath.class, EnvVars.class, /* DefaultStepContext infers from Computer: */ Node.class, Launcher.class);
        }

        @Override public Set<? extends Class<?>> getRequiredContext() {
            return ImmutableSet.of(TaskListener.class, FlowNode.class);
        }

        @Override public String getFunctionName() {
            return "dockerNode";
        }

        @Override public String getDisplayName() {
            return "Docker Node";
        }

    }

}
