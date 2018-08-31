package org.aion.cli;

import java.io.File;

import org.aion.avm.core.ShadowCoverageTarget;
import org.aion.avm.core.dappreading.JarBuilder;
import org.aion.avm.core.util.Helpers;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;


public class AvmCLIIntegrationTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void usage() {
        TestEnvironment env = new TestEnvironment("Usage: AvmCLI [options] [command] [command options]");
        AvmCLI.testingMain(env, new String[0]);
        Assert.assertTrue(env.didScrapeString);
        Assert.assertNull(env.capturedAddress);
    }

    @Test
    public void exploreShadowCoverageTarget() throws Exception {
        // Create the JAR and write it to a location we can parse from the command-line.
        byte[] jar = JarBuilder.buildJarForMainAndClasses(ShadowCoverageTarget.class);
        File temp = this.folder.newFile();
        Helpers.writeBytesToFile(jar, temp.getAbsolutePath());
        
        // Create the testing environment to look for the successful deployment.
        TestEnvironment deployEnv = new TestEnvironment("Result status: SUCCESS");
        AvmCLI.testingMain(deployEnv, new String[] {"deploy", temp.getAbsolutePath()});
        Assert.assertTrue(deployEnv.didScrapeString);
        String dappAddress = deployEnv.capturedAddress;
        Assert.assertNotNull(dappAddress);
        
        // Now, issue a call.
        TestEnvironment callEnv = new TestEnvironment("Result status: SUCCESS");
        AvmCLI.testingMain(callEnv, new String[] {"call", dappAddress, "--method", "populate_JavaLang"});
        Assert.assertTrue(callEnv.didScrapeString);
        
        // Now, check the storage.
        // (note that this NPE is just something in an instance field, as an example of deep data).
        TestEnvironment exploreEnv = new TestEnvironment("NullPointerException(30):");
        AvmCLI.testingMain(exploreEnv, new String[] {"explore", dappAddress});
        Assert.assertTrue(exploreEnv.didScrapeString);
    }


    private static class TestEnvironment implements IEnvironment {
        public final String requiredScrape;
        public String capturedAddress;
        public boolean didScrapeString;
        
        public TestEnvironment(String requiredScrape) {
            this.requiredScrape = requiredScrape;
        }
        
        @Override
        public RuntimeException fail(String message) {
            throw new RuntimeException(message);
        }
        @Override
        public void noteRelevantAddress(String address) {
            this.capturedAddress = address;
        }
        @Override
        public void logLine(String line) {
            if (null != this.requiredScrape) {
                if (line.contains(this.requiredScrape)) {
                    this.didScrapeString = true;
                }
            }
        }
    }
}