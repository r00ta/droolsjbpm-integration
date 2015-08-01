/*
 * Copyright 2015 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package org.kie.server.integrationtests.drools;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.List;

import com.thoughtworks.xstream.XStream;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.kie.api.KieServices;
import org.kie.api.command.BatchExecutionCommand;
import org.kie.api.command.Command;
import org.kie.api.command.KieCommands;
import org.kie.api.runtime.ExecutionResults;
import org.kie.internal.runtime.helper.BatchExecutionHelper;
import org.kie.scanner.MavenRepository;
import org.kie.server.api.KieServerEnvironment;
import org.kie.server.api.commands.CallContainerCommand;
import org.kie.server.api.commands.CommandScript;
import org.kie.server.api.commands.CreateContainerCommand;
import org.kie.server.api.commands.DisposeContainerCommand;
import org.kie.server.api.model.KieContainerResource;
import org.kie.server.api.model.KieScannerResource;
import org.kie.server.api.model.KieScannerStatus;
import org.kie.server.api.model.KieServerCommand;
import org.kie.server.api.model.KieServerInfo;
import org.kie.server.api.model.ReleaseId;
import org.kie.server.api.model.ServiceResponse;
import org.kie.server.api.model.ServiceResponse.ResponseType;
import org.kie.server.api.model.ServiceResponsesList;
import org.kie.server.integrationtests.shared.RestJmsXstreamSharedBaseIntegrationTest;

public class KieServerDroolsIntegrationTest extends RestJmsXstreamSharedBaseIntegrationTest {
    private static ReleaseId releaseId1 = new ReleaseId("foo.bar", "baz", "2.1.0.GA");
    private static ReleaseId releaseId2 = new ReleaseId("foo.bar", "baz", "2.1.1.GA");

    @BeforeClass
    public static void initialize() throws Exception {
        createAndDeployKJar(releaseId1);
        createAndDeployKJar(releaseId2);
    }

    @Test
    public void testCallContainer() throws Exception {
        client.createContainer("kie1", new KieContainerResource("kie1", releaseId1));

        String payload = "<batch-execution lookup=\"defaultKieSession\">\n" +
                "  <insert out-identifier=\"message\">\n" +
                "    <org.pkg1.Message>\n" +
                "      <text>Hello World</text>\n" +
                "    </org.pkg1.Message>\n" +
                "  </insert>\n" +
                "  <fire-all-rules/>\n" +
                "</batch-execution>";

        ServiceResponse<String> reply = ruleClient.executeCommands("kie1", payload);
        Assert.assertEquals(ServiceResponse.ResponseType.SUCCESS, reply.getType());
    }

    @Test
    public void testCallContainerMarshallCommands() throws Exception {
        client.createContainer("kie1", new KieContainerResource("kie1", releaseId1));

        KieServices ks = KieServices.Factory.get();
        File jar = MavenRepository.getMavenRepository().resolveArtifact(releaseId1).getFile();
        URLClassLoader cl = new URLClassLoader(new URL[]{jar.toURI().toURL()});
        Class<?> messageClass = cl.loadClass("org.pkg1.Message");
        Object message = messageClass.newInstance();
        Method setter = messageClass.getMethod("setText", String.class);
        Method getter = messageClass.getMethod("getText");
        setter.invoke( message, "HelloWorld");

        KieCommands kcmd = ks.getCommands();
        Command<?> insert = kcmd.newInsert(message, "message");
        Command<?> fire = kcmd.newFireAllRules();
        BatchExecutionCommand batch = kcmd.newBatchExecution(Arrays.asList(insert, fire), "defaultKieSession");

        ServiceResponse<String> reply = ruleClient.executeCommands("kie1", batch);
        Assert.assertEquals(ServiceResponse.ResponseType.SUCCESS, reply.getType());

        XStream xs = BatchExecutionHelper.newXStreamMarshaller();
        xs.setClassLoader(cl);
        ExecutionResults results = (ExecutionResults) xs.fromXML(reply.getResult());
        Object value = results.getValue("message");
        Assert.assertEquals("echo:HelloWorld", getter.invoke(value));
        cl.close();
    }

    @Test
    public void testCommandScript() throws Exception {
        KieServices ks = KieServices.Factory.get();
        File jar = MavenRepository.getMavenRepository().resolveArtifact(releaseId1).getFile();
        URLClassLoader cl = new URLClassLoader(new URL[]{jar.toURI().toURL()});
        Class<?> messageClass = cl.loadClass("org.pkg1.Message");
        Object message = messageClass.newInstance();
        Method setter = messageClass.getMethod("setText", String.class);
        setter.invoke(message, "HelloWorld");

        KieCommands kcmd = ks.getCommands();
        Command<?> insert = kcmd.newInsert(message, "message");
        Command<?> fire = kcmd.newFireAllRules();
        BatchExecutionCommand batch = kcmd.newBatchExecution(Arrays.asList(insert, fire), "defaultKieSession");

        String payload = BatchExecutionHelper.newXStreamMarshaller().toXML(batch);

        String containerId = "command-script-container";
        KieServerCommand create = new CreateContainerCommand(new KieContainerResource( containerId, releaseId1, null));
        KieServerCommand call = new CallContainerCommand(containerId, payload);
        KieServerCommand dispose = new DisposeContainerCommand(containerId);

        List<KieServerCommand> cmds = Arrays.asList(create, call, dispose);
        CommandScript script = new CommandScript(cmds);

        ServiceResponsesList reply = client.executeScript(script);

        for (ServiceResponse<? extends Object> r : reply.getResponses()) {
            Assert.assertEquals(ServiceResponse.ResponseType.SUCCESS, r.getType());
        }
        cl.close();
    }

    @Test
    public void testCallContainerLookupError() throws Exception {
        client.createContainer("kie1", new KieContainerResource("kie1", releaseId1));

        String payload = "<batch-execution lookup=\"xyz\">\n" +
                "  <insert out-identifier=\"message\">\n" +
                "    <org.pkg1.Message>\n" +
                "      <text>Hello World</text>\n" +
                "    </org.pkg1.Message>\n" +
                "  </insert>\n" +
                "</batch-execution>";

        ServiceResponse<String> reply = ruleClient.executeCommands("kie1", payload);
        Assert.assertEquals(ServiceResponse.ResponseType.FAILURE, reply.getType());
    }
}