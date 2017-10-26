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

import java.io.InputStream;
import org.apache.commons.io.IOUtils;
import static org.hamcrest.Matchers.*;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import static org.junit.Assert.*;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.Rule;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.WithoutJenkins;

public class DockerNodeStepTest {

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();

    @Rule public JenkinsRule r = new JenkinsRule();

    @WithoutJenkins
    @Test public void isValidImageWithTag() {
        assertFalse(DockerNodeStep.isValidImageWithTag(""));
        assertTrue(DockerNodeStep.isValidImageWithTag("jenkinsci/jenkins"));
        assertTrue(DockerNodeStep.isValidImageWithTag("mycontainer"));
        assertTrue(DockerNodeStep.isValidImageWithTag("mycorp.com/mycontainer"));
        assertTrue(DockerNodeStep.isValidImageWithTag("my-corp.com:9999/my-container"));
        assertTrue(DockerNodeStep.isValidImageWithTag("mycontainer:v1.0"));
        assertFalse(DockerNodeStep.isValidImageWithTag("mycontainer:v1.0 --option"));
        assertFalse(DockerNodeStep.isValidImageWithTag("MyContainer"));
    }

    @Test public void smokes() throws Exception {
        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("dockerNode {sh 'jps -lm'}", true));
        r.assertLogContains("1 /usr/share/jenkins/slave.jar", r.buildAndAssertSuccess(p));
    }

    @Test public void useBuildTools() throws Exception {
        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("dockerNode(image: 'cloudbees/jnlp-slave-with-java-build-tools') {sh 'cf --version > v.txt'; archiveArtifacts 'v.txt'}", true));
        WorkflowRun b = r.buildAndAssertSuccess(p);
        try (InputStream is = b.getArtifactManager().root().child("v.txt").open()) {
            assertThat(IOUtils.toString(is), startsWith("cf version "));
        }
    }

}