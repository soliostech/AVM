package org.aion.avm.core;

import org.aion.avm.internal.CommonInstrumentation;
import org.aion.avm.internal.IInstrumentation;
import org.aion.avm.internal.IInstrumentationFactory;
import org.aion.avm.internal.OutOfEnergyException;


/**
 * An implementation of IInstrumentationFactory intended for simulating various failure cases.
 * It will invoke a given Runnable after a specified number of basic blocks have been successfully billed.
 * This allows for simulations of things like OutOfEnergy at unusual times, asynchronous RuntimeExceptions when not expected,
 * or serious VirtualMachineErrors in sensitive areas.
 */
public class MockFailureInstrumentationFactory implements IInstrumentationFactory {
    private final int basicBlockIndex;
    private final Runnable toRun;

    public MockFailureInstrumentationFactory(int basicBlockIndex, Runnable toRun) {
        this.basicBlockIndex = basicBlockIndex;
        this.toRun = toRun;
    }

    @Override
    public IInstrumentation createInstrumentation() {
        CommonInstrumentation underlying = new CommonInstrumentation();
        return new IInstrumentation() {
            private int count;
            @Override
            public void enterNewFrame(ClassLoader contractLoader, long energyLeft, int nextHashCode) {
                underlying.enterNewFrame(contractLoader, energyLeft, nextHashCode);
            }
            @Override
            public void exitCurrentFrame() {
                underlying.exitCurrentFrame();
            }
            @Override
            public <T> org.aion.avm.shadow.java.lang.Class<T> wrapAsClass(java.lang.Class<T> input) {
                return underlying.wrapAsClass(input);
            }
            @Override
            public org.aion.avm.shadow.java.lang.String wrapAsString(String input) {
                return underlying.wrapAsString(input);
            }
            @Override
            public org.aion.avm.shadow.java.lang.Object unwrapThrowable(Throwable t) {
                return underlying.unwrapThrowable(t);
            }
            @Override
            public Throwable wrapAsThrowable(org.aion.avm.shadow.java.lang.Object arg) {
                return underlying.wrapAsThrowable(arg);
            }
            @Override
            public void chargeEnergy(long cost) throws OutOfEnergyException {
                int thisIndex = this.count;
                this.count += 1;
                if (thisIndex == MockFailureInstrumentationFactory.this.basicBlockIndex) {
                    MockFailureInstrumentationFactory.this.toRun.run();
                }
                underlying.chargeEnergy(cost);
            }
            @Override
            public long energyLeft() {
                return underlying.energyLeft();
            }
            @Override
            public int getNextHashCodeAndIncrement() {
                return underlying.getNextHashCodeAndIncrement();
            }
            @Override
            public void setAbortState() {
                underlying.setAbortState();
            }
            @Override
            public void clearAbortState() {
                underlying.clearAbortState();
            }
            @Override
            public int getCurStackSize() {
                return underlying.getCurStackSize();
            }
            @Override
            public int getCurStackDepth() {
                return underlying.getCurStackDepth();
            }
            @Override
            public void enterMethod(int frameSize) {
                underlying.enterMethod(frameSize);
            }
            @Override
            public void exitMethod(int frameSize) {
                underlying.exitMethod(frameSize);
            }
            @Override
            public void enterCatchBlock(int depth, int size) {
                underlying.enterCatchBlock(depth, size);
            }
            @Override
            public int peekNextHashCode() {
                return underlying.peekNextHashCode();
            }
            @Override
            public void forceNextHashCode(int nextHashCode) {
                underlying.forceNextHashCode(nextHashCode);
            }
            @Override
            public void bootstrapOnly() {
                underlying.bootstrapOnly();
            }
        };
    }
    @Override
    public void destroyInstrumentation(IInstrumentation instance) {
        // Implementation requires no cleanup.
    }
}
