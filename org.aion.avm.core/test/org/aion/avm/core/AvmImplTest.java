package org.aion.avm.core;

import java.math.BigInteger;
import org.aion.avm.api.ABIEncoder;
import org.aion.avm.api.Address;
import org.aion.avm.core.classloading.AvmClassLoader;
import org.aion.avm.core.dappreading.JarBuilder;
import org.aion.avm.core.types.RawDappModule;
import org.aion.avm.core.util.CodeAndArguments;
import org.aion.avm.core.util.Helpers;
import org.aion.avm.core.util.TestingHelper;
import org.aion.avm.internal.AvmThrowable;
import org.aion.avm.internal.CommonInstrumentation;
import org.aion.avm.internal.Helper;
import org.aion.avm.internal.IInstrumentation;
import org.aion.avm.internal.IRuntimeSetup;
import org.aion.avm.internal.InstrumentationHelpers;
import org.aion.avm.internal.JvmError;
import org.aion.avm.internal.OutOfEnergyException;
import org.aion.kernel.Block;
import org.aion.kernel.KernelInterface;
import org.aion.kernel.KernelInterfaceImpl;
import org.aion.kernel.TransactionContextImpl;
import org.aion.kernel.Transaction;
import org.aion.kernel.TransactionContext;
import org.aion.kernel.TransactionResult;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;

import static java.lang.String.format;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;


public class AvmImplTest {
    private static byte[] deployer = KernelInterfaceImpl.PREMINED_ADDRESS;
    private static Block block;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @BeforeClass
    public static void setupClass() {
        block = new Block(new byte[32], 1, Helpers.randomBytes(Address.LENGTH), System.currentTimeMillis(), new byte[0]);
    }

    @Test
    public void testStateUpdates() {
        KernelInterfaceImpl kernel = new KernelInterfaceImpl();
        Avm avm = CommonAvmFactory.buildAvmInstance(kernel);

        byte[] from = deployer;
        byte[] to = new byte[32];
        BigInteger value = BigInteger.valueOf(1000L);
        byte[] data = "data".getBytes();
        long energyLimit = 50_000L;
        long energyPrice = 1L;
        Transaction tx = Transaction.call(from, to, kernel.getNonce(from), value, data, energyLimit, energyPrice);
        TransactionResult result = avm.run(new TransactionContext[] {new TransactionContextImpl(tx, block)})[0].get();

        // verify results
        assertTrue(result.getStatusCode().isSuccess());
        assertNull(result.getReturnData());
        assertEquals(tx.getTransactionCost(), result.getEnergyUsed());
        assertEquals(0, result.getLogs().size());
        assertEquals(0, result.getInternalTransactions().size());

        // verify state change
        assertEquals(1, kernel.getNonce(from));
        assertEquals(KernelInterfaceImpl.PREMINED_AMOUNT.subtract(value).subtract(BigInteger.valueOf(tx.getTransactionCost() * energyPrice)), kernel.getBalance(deployer));
        assertEquals(0, kernel.getNonce(to));
        assertEquals(value, kernel.getBalance(to));
        avm.shutdown();
    }

    @Test
    public void checkMainClassHasProperName() throws IOException {
        final var module = "com.example.avmstartuptest";
        final Path path = Paths.get(format("%s/%s.jar", "../examples/build", module));
        final byte[] jar = Files.readAllBytes(path);
        final RawDappModule dappModule = RawDappModule.readFromJar(jar);
        final var mainClassName = "com.example.avmstartuptest.MainClass";
        assertEquals(mainClassName, dappModule.mainClass);
        Map<String, byte[]> classes = dappModule.classes;
        assertEquals(1, classes.size());
        final var expectedSizeOfFile = 424;
        assertEquals(expectedSizeOfFile, classes.get(mainClassName).length);
    }

    @Test
    public void testJvmError() {
        // Note that we eventually need to test how this interacts with AvmImpl's contract entry-point but this at least proves
        // that the hierarchy is correctly put together.
        String result = null;
        try {
            throw new JvmError(new UnknownError("testing"));
        } catch (AvmThrowable e) {
            result = e.getMessage();
        }
        assertEquals("java.lang.UnknownError: testing", result);
    }

    /**
     * Tests that, if we hit the energy limit, we continue to hit it on every attempt to charge for a new code block.
     */
    @Test
    public void testPersistentEnergyLimit() {
        // Set up the runtime (note that we need to initialize the NodeEnvironment before we attach to the thread)..
        Map<String, byte[]> contractClasses = Helpers.mapIncludingHelperBytecode(Collections.emptyMap(), Helpers.loadDefaultHelperBytecode());
        AvmClassLoader avmClassLoader = NodeEnvironment.singleton.createInvocationClassLoader(contractClasses);
        
        IRuntimeSetup runtimeSetup = new Helper();
        IInstrumentation instrumentation = new CommonInstrumentation();
        InstrumentationHelpers.attachThread(instrumentation);
        InstrumentationHelpers.pushNewStackFrame(runtimeSetup, avmClassLoader, 5L, 1);

        // Prove that we can charge 0 without issue.
        instrumentation.chargeEnergy(0);
        assertEquals(5, instrumentation.energyLeft());

        // Run the test.
        int catchCount = 0;
        OutOfEnergyException error = null;
        try {
            instrumentation.chargeEnergy(10);
        } catch (OutOfEnergyException e) {
            catchCount += 1;
            error = e;
        }
        // We didn't reset the state so this should still fail.
        try {
            instrumentation.chargeEnergy(0);
        } catch (OutOfEnergyException e) {
            catchCount += 1;
            // And have the same exception.
            assertEquals(error, e);
        }
        assertEquals(2, catchCount);
        InstrumentationHelpers.detachThread(instrumentation);
    }

    @Test
    public void testHelperStateRestore() {
        byte[] jar = JarBuilder.buildJarForMainAndClasses(AvmImplTestResource.class);
        byte[] arguments = new byte[0];
        byte[] txData = new CodeAndArguments(jar, arguments).encodeToBytes();
        KernelInterfaceImpl kernel = new KernelInterfaceImpl();
        Avm avm = CommonAvmFactory.buildAvmInstance(kernel);

        // deploy
        long energyLimit = 1_000_000l;
        long energyPrice = 1l;
        Transaction tx1 = Transaction.create(deployer, kernel.getNonce(deployer), BigInteger.ZERO, txData, energyLimit, energyPrice);
        TransactionResult result1 = avm.run(new TransactionContext[] {new TransactionContextImpl(tx1, block)})[0].get();
        assertEquals(TransactionResult.Code.SUCCESS, result1.getStatusCode());

        Address contractAddr = TestingHelper.buildAddress(result1.getReturnData());

        // Account for the cost:  deployment, clinit, init call.
        long basicCost = BillingRules.getBasicTransactionCost(txData);
        long codeInstantiationOfDeploymentFee = BillingRules.getDeploymentFee(1, jar.length);
        long codeStorageOfDeploymentFee = BillingRules.getCodeStorageFee(jar.length);
        long clinitCost = 188l;
        // Storage:  static 64 bytes (2 references) +  the 2 strings (hash code and string length: "CALL" + "NORMAL").
        long initialStorageCost = (3 * InstrumentationBasedStorageFees.PER_OBJECT_WRITE_NEW)
                + (64 * InstrumentationBasedStorageFees.BYTE_WRITE_COST)
                + (byteSizeOfSerializedString("CALL") * InstrumentationBasedStorageFees.BYTE_WRITE_COST)
                + (byteSizeOfSerializedString("NORMAL") * InstrumentationBasedStorageFees.BYTE_WRITE_COST)
                ;
        assertEquals(basicCost + codeInstantiationOfDeploymentFee + codeStorageOfDeploymentFee + clinitCost + initialStorageCost, result1.getEnergyUsed());

        // call (1 -> 2 -> 2)
        long transaction2EnergyLimit = 1_000_000l;
        Transaction tx2 = Transaction.call(deployer, contractAddr.unwrap(), kernel.getNonce(deployer), BigInteger.ZERO, contractAddr.unwrap(), transaction2EnergyLimit, energyPrice);
        TransactionResult result2 = avm.run(new TransactionContext[] {new TransactionContextImpl(tx2, block)})[0].get();
        assertEquals(TransactionResult.Code.SUCCESS, result2.getStatusCode());
        assertArrayEquals("CALL".getBytes(), result2.getReturnData());
        // Account for the cost:  (blocks in call method) + runtime.call
        long costOfBlocks = 111l + 57l + 509l;
        long costOfRuntimeCall = 111l + 57l + 116l + 2577l;
        // All persistence load/store cost (note that this is a reentrant call):
        long runStorageCost = 0L
        // -read statics (outer)
                + (InstrumentationBasedStorageFees.FIXED_READ_COST + (64 * InstrumentationBasedStorageFees.BYTE_READ_COST))
        // -read statics (inner)
                + (InstrumentationBasedStorageFees.FIXED_READ_COST + (64 * InstrumentationBasedStorageFees.BYTE_READ_COST))
        // -read instance (outer) "NORMAL" (free because we are just loading it _for_ the inner case)
        //        + (InstrumentationBasedStorageFees.FIXED_READ_COST + (byteSizeOfSerializedString("NORMAL") * InstrumentationBasedStorageFees.BYTE_READ_COST))
        // -read instance (inner) "NORMAL"
                + (InstrumentationBasedStorageFees.FIXED_READ_COST + (byteSizeOfSerializedString("NORMAL") * InstrumentationBasedStorageFees.BYTE_READ_COST))
        // -write statics (inner)
        //        + (InstrumentationBasedStorageFees.PER_OBJECT_WRITE_UPDATE + (64 * InstrumentationBasedStorageFees.BYTE_WRITE_COST))
        // -write instance (inner) "NORMAL"
        //        + (InstrumentationBasedStorageFees.PER_OBJECT_WRITE_UPDATE + (byteSizeOfSerializedString("NORMAL") * InstrumentationBasedStorageFees.BYTE_WRITE_COST))
        // -read instance (outer) "CALL"
                + (InstrumentationBasedStorageFees.FIXED_READ_COST + (byteSizeOfSerializedString("CALL") * InstrumentationBasedStorageFees.BYTE_READ_COST))
        // -write statics (outer)
        //        + (InstrumentationBasedStorageFees.PER_OBJECT_WRITE_UPDATE + (64 * InstrumentationBasedStorageFees.BYTE_WRITE_COST))
        // -write instance (outer) "CALL"
        //        + (InstrumentationBasedStorageFees.PER_OBJECT_WRITE_UPDATE + (byteSizeOfSerializedString("CALL") * InstrumentationBasedStorageFees.BYTE_WRITE_COST))
        // -write instance (outer) "NORMAL" (free because we didn't touch it, just loaded it for the inner case)
        //        + (InstrumentationBasedStorageFees.FIXED_WRITE_COST + (byteSizeOfSerializedString("NORMAL") * InstrumentationBasedStorageFees.BYTE_WRITE_COST))
                ;
        long runtimeCost = 4073;
        assertEquals(runtimeCost + tx2.getTransactionCost() + costOfBlocks + costOfRuntimeCall + runStorageCost, result2.getEnergyUsed()); // NOTE: the numbers are not calculated, but for fee schedule change detection.

        avm.shutdown();
    }

    @Test
    public void testNullReturnCrossCall() {
        byte[] jar = JarBuilder.buildJarForMainAndClasses(ReentrantCrossCallResource.class);
        byte[] txData = new CodeAndArguments(jar, new byte[0]).encodeToBytes();
        KernelInterfaceImpl kernel = new KernelInterfaceImpl();
        Avm avm = CommonAvmFactory.buildAvmInstance(kernel);
        
        // deploy
        Address contractAddr = createDApp(kernel, avm, txData);
        
        // Call the callSelfForNull entry-point and it should return null to us.
        byte[] argData = ABIEncoder.encodeMethodArguments("callSelfForNull");
        Object resultObject = callDApp(kernel, avm, contractAddr, argData);
        assertNull(resultObject);
        avm.shutdown();
    }

    @Test
    public void testRecursiveHashCode() {
        byte[] jar = JarBuilder.buildJarForMainAndClasses(ReentrantCrossCallResource.class);
        byte[] txData = new CodeAndArguments(jar, new byte[0]).encodeToBytes();
        KernelInterfaceImpl kernel = new KernelInterfaceImpl();
        Avm avm = CommonAvmFactory.buildAvmInstance(kernel);
        
        // deploy
        long energyLimit = 1_000_000l;
        Address contractAddr = createDApp(kernel, avm, txData);
        
        // Try a few invocations of different depths, bearing in mind the change of nextHashCode between each invocation.
        // We will do 2 zero-depth calls to see the delta between the 2 calls.
        // Then, we will do an indirect call and verify that the delta is greater.
        // If the hashcode wasn't restored across reentrant calls, this wouldn't be greater as it wouldn't capture the small cost of the original
        // indirect call (since we create at least 1 object in that path).
        int zero0 = callRecursiveHash(kernel, avm, energyLimit, contractAddr, 0);
        int zero1 = callRecursiveHash(kernel, avm, energyLimit, contractAddr, 0);
        int zero2 = callRecursiveHash(kernel, avm, energyLimit, contractAddr, 0);
        int one0 = callRecursiveHash(kernel, avm, energyLimit, contractAddr, 1);
        int one1 = callRecursiveHash(kernel, avm, energyLimit, contractAddr, 1);
        int one2 = callRecursiveHash(kernel, avm, energyLimit, contractAddr, 1);
        
        assertEquals(zero1 - zero0, zero2 - zero1);
        assertEquals(one1 - one0, one2 - one1);
        assertTrue((one1 - one0) > (zero1 - zero0));
        avm.shutdown();
    }

    /**
     * Tests that reentrant calls do have detectable side-effects within the caller's space, when they commit.
     */
    @Test
    public void testCommitReentrantCalls() {
        boolean shouldFail = false;
        byte[] jar = JarBuilder.buildJarForMainAndClasses(ReentrantCrossCallResource.class);
        byte[] txData = new CodeAndArguments(jar, new byte[0]).encodeToBytes();
        KernelInterfaceImpl kernel = new KernelInterfaceImpl();
        Avm avm = CommonAvmFactory.buildAvmInstance(kernel);
        
        // deploy
        Address contractAddr = createDApp(kernel, avm, txData);
        
        // Get direct increments from 1 to 2 and returns 2.
        assertEquals(2, callReentrantAccess(kernel, avm, contractAddr, "getDirect", shouldFail));
        
        // Get near increments from 1 to 2 and returns 2.
        assertEquals(2, callReentrantAccess(kernel, avm, contractAddr, "getNear", shouldFail));
        
        // Get far increments from 1 to 2 and returns 2.
        assertEquals(2, callReentrantAccess(kernel, avm, contractAddr, "getFar", shouldFail));
        
        // Get near increments from 2 to 3 and returns 3.
        assertEquals(3, callReentrantAccess(kernel, avm, contractAddr, "getNear", shouldFail));
        avm.shutdown();
    }

    /**
     * Tests that reentrant calls do NOT have detectable side-effects within the caller's space, when they rollback.
     */
    @Test
    public void testRollbackReentrantCalls() {
        boolean shouldFail = true;
        byte[] jar = JarBuilder.buildJarForMainAndClasses(ReentrantCrossCallResource.class);
        byte[] txData = new CodeAndArguments(jar, new byte[0]).encodeToBytes();
        KernelInterfaceImpl kernel = new KernelInterfaceImpl();
        Avm avm = CommonAvmFactory.buildAvmInstance(kernel);
        
        // deploy
        Address contractAddr = createDApp(kernel, avm, txData);
        
        // We expect these to all fail, so they should be left with the initial values:  1.
        assertEquals(1, callReentrantAccess(kernel, avm, contractAddr, "getDirect", shouldFail));
        assertEquals(1, callReentrantAccess(kernel, avm, contractAddr, "getNear", shouldFail));
        assertEquals(1, callReentrantAccess(kernel, avm, contractAddr, "getFar", shouldFail));
        assertEquals(1, callReentrantAccess(kernel, avm, contractAddr, "getNear", shouldFail));
        avm.shutdown();
    }

    /**
     * Tests that reentrant calls do not accidentally write-back the statics, even when the caller fails.
     */
    @Test
    public void testRollbackAfterReentrantSuccess() {
        boolean shouldFail = true;
        byte[] jar = JarBuilder.buildJarForMainAndClasses(ReentrantCrossCallResource.class);
        byte[] txData = new CodeAndArguments(jar, new byte[0]).encodeToBytes();
        KernelInterfaceImpl kernel = new KernelInterfaceImpl();
        Avm avm = CommonAvmFactory.buildAvmInstance(kernel);
        
        // deploy
        long energyLimit = 1_000_000l;
        Address contractAddr = createDApp(kernel, avm, txData);
        
        // Cause the failure.
        byte[] nearData = ABIEncoder.encodeMethodArguments("localFailAfterReentrant");
        Transaction tx = Transaction.call(deployer, contractAddr.unwrap(), kernel.getNonce(deployer), BigInteger.ZERO, nearData, energyLimit, 1L);
        TransactionResult result2 = avm.run(new TransactionContext[] {new TransactionContextImpl(tx, block)})[0].get();
        assertEquals(TransactionResult.Code.FAILED_OUT_OF_ENERGY, result2.getStatusCode());
        
        // We shouldn't see any changes, since this failed.
        // We expect these to all fail, so they should be left with the initial values:  1.
        assertEquals(1, callReentrantAccess(kernel, avm, contractAddr, "getDirect", shouldFail));
        assertEquals(1, callReentrantAccess(kernel, avm, contractAddr, "getNear", shouldFail));
        assertEquals(1, callReentrantAccess(kernel, avm, contractAddr, "getFar", shouldFail));
        assertEquals(1, callReentrantAccess(kernel, avm, contractAddr, "getNear", shouldFail));
        avm.shutdown();
    }

    /**
     * Tests that reentrant calls do not leave any side-effects within the caller's space when the rollback only during the last part of write-back.
     */
    @Test
    public void testReentrantRollbackDuringCommit() {
        byte[] jar = JarBuilder.buildJarForMainAndClasses(ReentrantCrossCallResource.class);
        byte[] txData = new CodeAndArguments(jar, new byte[0]).encodeToBytes();
        KernelInterfaceImpl kernel = new KernelInterfaceImpl();
        Avm avm = CommonAvmFactory.buildAvmInstance(kernel);
        
        // deploy
        Address contractAddr = createDApp(kernel, avm, txData);
        
        // We just want to call our special getFar helper with a constrained energy.
        // WARNING:  This test is very sensitive to storage billing configuration so the energy limit likely needs to be updated when that changes.
        // The write-back of the callee attempts to write statics and 2 instances.  We want it to fail at 1 instance (20_000L seems to do this).
        long failingLimit = 20_000L;
        byte[] callData = ABIEncoder.encodeMethodArguments("getFarWithEnergy", failingLimit);
        Object resultObject = callDApp(kernel, avm, contractAddr, callData);

        
        // This returns false since the value didn't change,
        assertEquals(false, ((Boolean)resultObject).booleanValue());
        avm.shutdown();
    }

    /**
     * Tests that inner classes are correctly handled by reentrant calls.
     * Also ensures that instances reachable from statics are correctly handled to arbitrary recursive depth.
     */
    @Test
    public void testReentrantRecursiveNested() {
        byte[] jar = JarBuilder.buildJarForMainAndClasses(ReentrantCrossCallResource.class);
        byte[] txData = new CodeAndArguments(jar, new byte[0]).encodeToBytes();
        KernelInterfaceImpl kernel = new KernelInterfaceImpl();
        Avm avm = CommonAvmFactory.buildAvmInstance(kernel);
        
        // deploy
        Address contractAddr = createDApp(kernel, avm, txData);
        
        // We just want to call our special getFar helper with a constrained energy.
        // WARNING:  This test is very sensitive to storage billing configuration so the energy limit likely needs to be updated when that changes.
        // The write-back of the callee attempts to write statics and 2 instances.  We want it to fail at 1 instance (20_000L seems to do this).
        byte[] callData = ABIEncoder.encodeMethodArguments("recursiveChangeNested", 0, 5);
        Object resultObject = callDApp(kernel, avm, contractAddr, callData);
        
        // We don't want to depend on a specific hashcode (appears to be 19) but just the idea that it needs to be non-zero.
        assertTrue(0 != ((Integer)resultObject).intValue());

        avm.shutdown();
    }

    /**
    * Tests that the internal call depth limit is in effect; aka, "CallDepthLimitExceededException"
    * is thrown once the limit is reached.
    */
    @Test
    public void testCallDepthLimit() {
        byte[] jar = JarBuilder.buildJarForMainAndClasses(ReentrantCrossCallResource.class);
        byte[] txData = new CodeAndArguments(jar, new byte[0]).encodeToBytes();
        KernelInterfaceImpl kernel = new KernelInterfaceImpl();
        Avm avm = CommonAvmFactory.buildAvmInstance(kernel);

        // deploy
        Address contractAddr = createDApp(kernel, avm, txData);

        // Verify the internal call depth limit is in effect.
        byte[] callData = ABIEncoder.encodeMethodArguments("recursiveChangeNested", 0, 10);
        Transaction tx = Transaction.call(deployer, contractAddr.unwrap(), kernel.getNonce(deployer), BigInteger.ZERO, callData, 20_000_000l, 1L);
        TransactionResult result2 = avm.run(new TransactionContext[] {new TransactionContextImpl(tx, block)})[0].get();
        assertEquals(TransactionResult.Code.FAILED_EXCEPTION, result2.getStatusCode());

        avm.shutdown();
    }

    /**
     * Tests that a DApp can CREATE and then CALL another instance.
     */
    @Test
    public void testCreateAndCallSubApp() {
        byte incrementBy = 2;
        byte[] incrementorJar = JarBuilder.buildJarForMainAndClasses(IncrementorDApp.class);
        byte[] incrementorCreateData = new CodeAndArguments(incrementorJar, new byte[] {incrementBy}).encodeToBytes();
        byte[] spawnerJar = JarBuilder.buildJarForMainAndClasses(SpawnerDApp.class);
        byte[] spanerCreateData = new CodeAndArguments(spawnerJar, incrementorCreateData).encodeToBytes();
        KernelInterfaceImpl kernel = new KernelInterfaceImpl();
        Avm avm = CommonAvmFactory.buildAvmInstance(kernel);
        
        // CREATE the spawner.
        Address spawnerAddress = createDApp(kernel, avm, spanerCreateData);
        
        // CALL to create and invoke the incrementor.
        byte[] input = new byte[] {1,2,3,4,5};
        byte[] incrementorCallData = ABIEncoder.encodeMethodArguments("incrementArray", input);
        byte[] spawnerCallData = ABIEncoder.encodeMethodArguments("spawnAndCall", incrementorCallData);
        byte[] incrementorResult = (byte[]) callDApp(kernel, avm, spawnerAddress, spawnerCallData);
        // We double-encoded the arguments, so double-decode the response.
        byte[] spawnerResult = (byte[]) TestingHelper.decodeResultRaw(incrementorResult);
        assertEquals(input.length, spawnerResult.length);
        for (int i = 0; i < input.length; ++i) {
            assertEquals(incrementBy + input[i], spawnerResult[i]);
        }
        avm.shutdown();
    }

    /**
     * Tests that a DApp can CREATE for us.
     */
    @Test
    public void testCreateSubAppCall() {
        byte incrementBy = 3;
        byte[] incrementorJar = JarBuilder.buildJarForMainAndClasses(IncrementorDApp.class);
        byte[] incrementorCreateData = new CodeAndArguments(incrementorJar, new byte[] {incrementBy}).encodeToBytes();
        byte[] spawnerJar = JarBuilder.buildJarForMainAndClasses(SpawnerDApp.class);
        byte[] spanerCreateData = new CodeAndArguments(spawnerJar, incrementorCreateData).encodeToBytes();
        KernelInterfaceImpl kernel = new KernelInterfaceImpl();
        Avm avm = CommonAvmFactory.buildAvmInstance(kernel);
        
        // CREATE the spawner.
        Address spawnerAddress = createDApp(kernel, avm, spanerCreateData);
        
        // CALL to create and invoke the incrementor.
        boolean shouldFail = false;
        byte[] spawnerCallData = ABIEncoder.encodeMethodArguments("spawnOnly", shouldFail);
        Address incrementorAddress = (Address) callDApp(kernel, avm, spawnerAddress, spawnerCallData);
        
        // Call the incrementor, directly.
        byte[] input = new byte[] {1,2,3,4,5};
        byte[] incrementorCallData = ABIEncoder.encodeMethodArguments("incrementArray", input);
        
        byte[] incrementorResult = (byte[]) callDApp(kernel, avm, incrementorAddress, incrementorCallData);
        assertEquals(input.length, incrementorResult.length);
        for (int i = 0; i < input.length; ++i) {
            assertEquals(incrementBy + input[i], incrementorResult[i]);
        }
        avm.shutdown();
    }

    /**
     * Tests that a DApp can CREATE for us (but is reverted on failure).
     */
    @Test
    public void testCreateSubAppCallFailure() {
        byte incrementBy = 3;
        byte[] incrementorJar = JarBuilder.buildJarForMainAndClasses(IncrementorDApp.class);
        byte[] incrementorCreateData = new CodeAndArguments(incrementorJar, new byte[] {incrementBy}).encodeToBytes();
        byte[] spawnerJar = JarBuilder.buildJarForMainAndClasses(SpawnerDApp.class);
        byte[] spanerCreateData = new CodeAndArguments(spawnerJar, incrementorCreateData).encodeToBytes();
        KernelInterfaceImpl kernel = new KernelInterfaceImpl();
        Avm avm = CommonAvmFactory.buildAvmInstance(kernel);
        
        // CREATE the spawner.
        Address spawnerAddress = createDApp(kernel, avm, spanerCreateData);
        
        // CALL to create and invoke the incrementor.
        boolean shouldFail = true;
        byte[] spawnerCallData = ABIEncoder.encodeMethodArguments("spawnOnly", shouldFail);
        long energyLimit = 1_000_000l;
        Transaction tx = Transaction.call(KernelInterfaceImpl.PREMINED_ADDRESS, spawnerAddress.unwrap(), kernel.getNonce(deployer), BigInteger.ZERO, spawnerCallData, energyLimit, 1L);
        TransactionResult result2 = avm.run(new TransactionContext[] {new TransactionContextImpl(tx, block)})[0].get();
        assertEquals(TransactionResult.Code.FAILED_INVALID, result2.getStatusCode());
        avm.shutdown();
    }

    /**
     * Tests that a DApp can CREATE for us, backed by a directory.
     * (same as "testCreateSubAppCall" but uses a directory backing-store).
     */
    @Test
    public void testCreateSubAppCallOnDirectory() throws Exception {
        File directory = folder.newFolder();
        byte incrementBy = 3;
        byte[] incrementorJar = JarBuilder.buildJarForMainAndClasses(IncrementorDApp.class);
        byte[] incrementorCreateData = new CodeAndArguments(incrementorJar, new byte[] {incrementBy}).encodeToBytes();
        byte[] spawnerJar = JarBuilder.buildJarForMainAndClasses(SpawnerDApp.class);
        byte[] spanerCreateData = new CodeAndArguments(spawnerJar, incrementorCreateData).encodeToBytes();
        KernelInterfaceImpl kernel = new KernelInterfaceImpl(directory);
        Avm avm = CommonAvmFactory.buildAvmInstance(kernel);
        
        // We always start out with the PREMINE account, but that should be the only one.
        assertEquals(1, directory.listFiles().length);
        
        // CREATE the spawner (meaning another account). Expect 3 accounts because: deployer, contract, coinbase
        Address spawnerAddress = createDApp(kernel, avm, spanerCreateData);
        assertEquals(3, directory.listFiles().length);
        
        // CALL to create and invoke the incrementor. Expect 4 accounts because: deployer, contract1, contract2, coinbase
        boolean shouldFail = false;
        byte[] spawnerCallData = ABIEncoder.encodeMethodArguments("spawnOnly", shouldFail);
        Address incrementorAddress = (Address) callDApp(kernel, avm, spawnerAddress, spawnerCallData);
        assertEquals(4, directory.listFiles().length);
        
        // Restart the AVM.
        avm.shutdown();
        kernel = new KernelInterfaceImpl(directory);
        avm = CommonAvmFactory.buildAvmInstance(kernel);
        
        // Call the incrementor, directly.
        byte[] input = new byte[] {1,2,3,4,5};
        byte[] incrementorCallData = ABIEncoder.encodeMethodArguments("incrementArray", input);
        
        byte[] incrementorResult = (byte[]) callDApp(kernel, avm, incrementorAddress, incrementorCallData);
        assertEquals(input.length, incrementorResult.length);
        for (int i = 0; i < input.length; ++i) {
            assertEquals(incrementBy + input[i], incrementorResult[i]);
        }
        
        // Check the state of the directory we are using to back this (4 accounts, 2 with code and 2 with only a balance).
        int codeCount = 0;
        int balanceCount = 0;
        for (File top : directory.listFiles()) {
            for (File account : top.listFiles()) {
                if ("code".equals(account.getName())) {
                    codeCount += 1;
                } else if ("balance".equals(account.getName())) {
                    balanceCount += 1;
                }
            }
        }
        assertEquals(2, codeCount);
        assertEquals(4, balanceCount);
        avm.shutdown();
    }


    private int callRecursiveHash(KernelInterface kernel, Avm avm, long energyLimit, Address contractAddr, int depth) {
        byte[] argData = ABIEncoder.encodeMethodArguments("getRecursiveHashCode", depth);
        Transaction call = Transaction.call(deployer, contractAddr.unwrap(), kernel.getNonce(deployer), BigInteger.ZERO, argData, energyLimit, 1L);
        TransactionResult result = avm.run(new TransactionContext[] {new TransactionContextImpl(call, block)})[0].get();
        assertEquals(TransactionResult.Code.SUCCESS, result.getStatusCode());
        return ((Integer)TestingHelper.decodeResult(result)).intValue();
    }

    private int callReentrantAccess(KernelInterface kernel, Avm avm, Address contractAddr, String methodName, boolean shouldFail) {
        byte[] nearData = ABIEncoder.encodeMethodArguments(methodName, shouldFail);
        Object resultObject = callDApp(kernel, avm, contractAddr, nearData);
        return ((Integer)resultObject).intValue();
    }

    private int byteSizeOfSerializedString(String string) {
        // Hashcode(4) + length(4) + UTF-8 bytes.
        return (4 + 4 + string.getBytes(StandardCharsets.UTF_8).length);
    }

    private Address createDApp(KernelInterface kernel, Avm avm, byte[] createData) {
        long energyLimit = 10_000_000l;
        long energyPrice = 1l;
        Transaction tx1 = Transaction.create(deployer, kernel.getNonce(deployer), BigInteger.ZERO, createData, energyLimit, energyPrice);
        TransactionResult result1 = avm.run(new TransactionContext[] {new TransactionContextImpl(tx1, block)})[0].get();
        assertEquals(TransactionResult.Code.SUCCESS, result1.getStatusCode());
        return TestingHelper.buildAddress(result1.getReturnData());
    }

    private Object callDApp(KernelInterface kernel, Avm avm, Address dAppAddress, byte[] argData) {
        long energyLimit = 2_000_000l;
        Transaction tx = Transaction.call(deployer, dAppAddress.unwrap(), kernel.getNonce(deployer), BigInteger.ZERO, argData, energyLimit, 1L);
        TransactionResult result2 = avm.run(new TransactionContext[] {new TransactionContextImpl(tx, block)})[0].get();
        assertEquals(TransactionResult.Code.SUCCESS, result2.getStatusCode());
        return TestingHelper.decodeResult(result2);
    }
}
