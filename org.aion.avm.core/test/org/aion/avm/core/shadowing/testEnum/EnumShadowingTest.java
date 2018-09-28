package org.aion.avm.core.shadowing.testEnum;

import org.aion.avm.api.ABIEncoder;
import org.aion.avm.api.Address;
import org.aion.avm.core.Avm;
import org.aion.avm.core.NodeEnvironment;
import org.aion.avm.core.TestingHelper;
import org.aion.avm.core.dappreading.JarBuilder;
import org.aion.avm.core.util.CodeAndArguments;
import org.aion.avm.core.util.Helpers;
import org.aion.kernel.*;
import org.junit.*;

public class EnumShadowingTest {
    private byte[] from = KernelInterfaceImpl.PREMINED_ADDRESS;
    private byte[] dappAddr;

    private Block block = new Block(new byte[32], 1, Helpers.randomBytes(Address.LENGTH), System.currentTimeMillis(), new byte[0]);
    private long energyLimit = 600_000_00000L;
    private long energyPrice = 1;

    private KernelInterfaceImpl kernel = new KernelInterfaceImpl();
    private Avm avm = NodeEnvironment.singleton.buildAvmInstance(kernel);

    @Before
    public void setup() {
        byte[] testJar = JarBuilder.buildJarForMainAndClasses(TestResource.class, TestEnum.class);
        byte[] txData = new CodeAndArguments(testJar, null).encodeToBytes();

        Transaction tx = new Transaction(Transaction.Type.CREATE, from, null, kernel.getNonce(from), 0, txData, energyLimit, energyPrice);
        TransactionContextImpl context = new TransactionContextImpl(tx, block);
        dappAddr = avm.run(context).getReturnData();
    }

    @Test
    public void testEnumAccess() {
        byte[] txData = ABIEncoder.encodeMethodArguments("testEnumAccess");
        Transaction tx = new Transaction(Transaction.Type.CALL, from, dappAddr, kernel.getNonce(from), 0, txData, energyLimit, energyPrice);
        TransactionContextImpl context = new TransactionContextImpl(tx, block);
        TransactionResult result = avm.run(context);

        Assert.assertEquals(true, TestingHelper.decodeResult(result));
    }

    @Test
    public void testEnumValues() {
        byte[] txData = ABIEncoder.encodeMethodArguments("testEnumValues");
        Transaction tx = new Transaction(Transaction.Type.CALL, from, dappAddr, kernel.getNonce(from), 0, txData, energyLimit, energyPrice);
        TransactionContextImpl context = new TransactionContextImpl(tx, block);
        TransactionResult result = avm.run(context);

        Assert.assertEquals(true, TestingHelper.decodeResult(result));
    }

    @Test
    public void testShadowJDKEnum() {
        byte[] txData = ABIEncoder.encodeMethodArguments("testShadowJDKEnum");
        Transaction tx = new Transaction(Transaction.Type.CALL, from, dappAddr, kernel.getNonce(from), 0, txData, energyLimit, energyPrice);
        TransactionContextImpl context = new TransactionContextImpl(tx, block);
        TransactionResult result = avm.run(context);

        Assert.assertEquals(true, TestingHelper.decodeResult(result));
    }
}
