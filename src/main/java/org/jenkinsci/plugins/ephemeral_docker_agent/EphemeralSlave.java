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

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.model.Slave;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.EphemeralNode;
import hudson.slaves.RetentionStrategy;
import java.io.IOException;

final class EphemeralSlave extends Slave implements EphemeralNode {

    EphemeralSlave(String name, String remoteFS, ComputerLauncher launcher) throws Descriptor.FormException, IOException {
        super(name, remoteFS, launcher);
        setNumExecutors(1);
        setMode(Mode.EXCLUSIVE);
        setRetentionStrategy(RetentionStrategy.NOOP);
    }

    @Override public Computer createComputer() {
        return new EphemeralComputer(this);
    }
    
    @Override public boolean isAcceptingTasks() {
        return false;
    }
    
    @Override public Node asNode() {
        return this;
    }
    
    @Extension public static class DescriptorImpl extends SlaveDescriptor {
        
        @Override public boolean isInstantiable() {
            return false;
        }
    }

}
