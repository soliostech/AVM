package org.aion.avm.core;

import java.math.BigInteger;
import org.aion.avm.api.ABIDecoder;
import org.aion.avm.api.ABIEncoder;
import org.aion.avm.api.BlockchainRuntime;

public class AvmFailureTestResource {


    public static void reentrantCall(int n) {
        if (n > 0) {
            byte[] data = ABIEncoder.encodeMethodArguments("reentrantCall", n - 1);
            BlockchainRuntime.call(BlockchainRuntime.getAddress(), BigInteger.ZERO, data, BlockchainRuntime.getEnergyLimit());
            BlockchainRuntime.log(new byte[]{(byte)n});
            BlockchainRuntime.revert();
        }
    }

    public static void testOutOfEnergy() {
        while (true) {
            byte[] bytes = new byte[1024];
            bytes.clone();
        }
    }

    private static void recursive(int n) {
        if (n > 0) {
            recursive(n - 1);
        }
    }

    public static void testOutOfStack() {
        recursive(1024);
    }

    public static void testRevert() {
        BlockchainRuntime.revert();
    }

    public static void testInvalid() {
        BlockchainRuntime.invalid();
    }

    public static void testUncaughtException() {
        byte[] bytes = new byte[2];
        bytes[3] = 1;
    }

    public static byte[] main() {
        return ABIDecoder.decodeAndRunWithClass(AvmFailureTestResource.class, BlockchainRuntime.getData());
    }
}
