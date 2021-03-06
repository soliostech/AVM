package org.aion.avm.core;

import org.aion.avm.core.persistence.IObjectGraphStore;
import org.aion.avm.core.util.Helpers;
import org.aion.kernel.*;

import java.io.IOException;
import java.lang.ref.SoftReference;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

import org.aion.avm.core.persistence.LoadedDApp;
import org.aion.avm.core.persistence.keyvalue.KeyValueObjectGraph;
import org.aion.avm.core.util.ByteArrayWrapper;
import org.aion.avm.core.util.SoftCache;
import org.aion.avm.internal.IInstrumentation;
import org.aion.avm.internal.IInstrumentationFactory;
import org.aion.avm.internal.InstrumentationHelpers;
import org.aion.avm.internal.JvmError;
import org.aion.avm.internal.RuntimeAssertionError;
import org.aion.parallel.AddressResourceMonitor;
import org.aion.parallel.TransactionTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.aion.avm.internal.RuntimeAssertionError.unexpected;


public class AvmImpl implements AvmInternal {

    private static final Logger logger = LoggerFactory.getLogger(AvmImpl.class);

    private static final boolean DEBUG_EXECUTOR = false;

    private static final int NUM_EXECUTORS = 4;

    private final IInstrumentationFactory instrumentationFactory;
    private KernelInterface kernel;

    // Long-lived state which is book-ended by the startup/shutdown calls.
    private static AvmImpl currentAvm;  // (only here for testing - makes sure that we properly clean these up between invocations)
    private SoftCache<ByteArrayWrapper, LoadedDApp> hotCache;
    private HandoffMonitor handoff;

    // Short-lived state which is reset for each batch of transaction request.
    private AddressResourceMonitor resourceMonitor;

    // Used in the case of a fatal JvmError in the background threads.  A shutdown() is the only option from this point.
    private AvmFailedException backgroundFatalError;

    public AvmImpl(IInstrumentationFactory instrumentationFactory, KernelInterface kernel) {
        this.instrumentationFactory = instrumentationFactory;
        this.kernel = kernel;
    }

    private class AvmExecutorThread extends Thread{

        AvmExecutorThread(String name){
            super(name);
        }

        @Override
        public void run() {
            IInstrumentation instrumentation = AvmImpl.this.instrumentationFactory.createInstrumentation();
            InstrumentationHelpers.attachThread(instrumentation);
            try {
                // Run as long as we have something to do (null means shutdown).
                TransactionResult outgoingResult = null;
                TransactionTask incomingTask = AvmImpl.this.handoff.blockingPollForTransaction(null, null);
                while (null != incomingTask) {
                    int abortCounter = 0;

                    do {
                        if (DEBUG_EXECUTOR) System.out.println(this.getName() + " start  " + incomingTask.getIndex());

                        // TODO:  Determine if we can coalesce the IInstrumentation and TransactionTask to avoid this attach/detach.
                        incomingTask.resetState();
                        incomingTask.attachInstrumentationForThread();
                        outgoingResult = AvmImpl.this.backgroundProcessTransaction(incomingTask);
                        incomingTask.detachInstrumentationForThread();

                        if (TransactionResult.Code.FAILED_ABORT == outgoingResult.getStatusCode()) {
                            // If this was an abort, we want to clear the abort state on the instrumentation for this thread, since
                            // this is the point where that is "handled".
                            // Note that this is safe to do here since the instrumentation isn't exposed to any other threads.
                            instrumentation.clearAbortState();
                            
                            if (DEBUG_EXECUTOR){
                                System.out.println(this.getName() + " abort  " + incomingTask.getIndex() + " counter " + (++abortCounter));
                            }
                        }
                    }while (TransactionResult.Code.FAILED_ABORT == outgoingResult.getStatusCode());

                    if (DEBUG_EXECUTOR) System.out.println(this.getName() + " finish " + incomingTask.getIndex() + " " + outgoingResult.getStatusCode());

                    incomingTask = AvmImpl.this.handoff.blockingPollForTransaction(outgoingResult, incomingTask);
                }
            } catch (JvmError e) {
                // This is a fatal error the AVM cannot generally happen so request an asynchronous shutdown.
                // We set the backgroundException without lock since any concurrently-written exception instance is equally valid.
                AvmFailedException backgroundFatalError = new AvmFailedException(e.getCause());
                AvmImpl.this.backgroundFatalError = backgroundFatalError;
                AvmImpl.this.handoff.setBackgroundThrowable(backgroundFatalError);
            } catch (Throwable t) {
                // Note that this case is primarily only relevant for unit tests or other new development which could cause internal exceptions.
                // Without this hand-off to the foreground thread, these exceptions would cause silent failures.
                // Uncaught exception - this is fatal but we need to communicate it to the outside.
                AvmImpl.this.handoff.setBackgroundThrowable(t);
                // If the throwable makes it all the way to here, we can't handle it.
                RuntimeAssertionError.unexpected(t);
            } finally {
                InstrumentationHelpers.detachThread(instrumentation);
                AvmImpl.this.instrumentationFactory.destroyInstrumentation(instrumentation);
            }
        }

    }

    public void startup() {
        RuntimeAssertionError.assertTrue(null == AvmImpl.currentAvm);
        AvmImpl.currentAvm = this;
        
        RuntimeAssertionError.assertTrue(null == this.hotCache);
        this.hotCache = new SoftCache<>();

        RuntimeAssertionError.assertTrue(null == this.resourceMonitor);
        this.resourceMonitor = new AddressResourceMonitor();

        Set<Thread> executorThreads = new HashSet<>();
        for (int i = 0; i < NUM_EXECUTORS; i++){
            executorThreads.add(new AvmExecutorThread("AVM Executor Thread " + i));
        }

        RuntimeAssertionError.assertTrue(null == this.handoff);
        this.handoff = new HandoffMonitor(executorThreads);
        this.handoff.startExecutorThreads();
    }

    @Override
    public SimpleFuture<TransactionResult>[] run(TransactionContext[] transactions) throws IllegalStateException {
        if (null != this.backgroundFatalError) {
            throw this.backgroundFatalError;
        }
        // Clear the states of resources
        this.resourceMonitor.clear();

        // Clear the hot cache
        if (transactions.length > 0) {
            long currentBlockNum = transactions[0].getBlockNumber();
            validateCodeCache(currentBlockNum);
        }

        return this.handoff.sendTransactionsAsynchronously(transactions);
    }

    private TransactionResult backgroundProcessTransaction(TransactionTask task) {
        // to capture any error during validation
        TransactionResult.Code error = null;

        RuntimeAssertionError.assertTrue(task != null);
        TransactionContext ctx = task.getExternalTransactionCtx();
        RuntimeAssertionError.assertTrue(ctx != null);

        // All IO will be performed on an per task transactional kernel so we can abort the whole task in one go
        TransactionalKernel taskTransactionalKernel = new TransactionalKernel(this.kernel);
        task.setTaskKernel(taskTransactionalKernel);

        // value/energyPrice/energyLimit sanity check
        if ((ctx.getValue().compareTo(BigInteger.ZERO) < 0) || (ctx.getEnergyPrice() <= 0)) {
            error = TransactionResult.Code.REJECTED;
        }
        
        if (ctx.isCreate()) {
            if (!taskTransactionalKernel.isValidEnergyLimitForCreate(ctx.getEnergyLimit())) {
                error = TransactionResult.Code.REJECTED;
            }
        } else {
            if (!taskTransactionalKernel.isValidEnergyLimitForNonCreate(ctx.getEnergyLimit())) {
                error = TransactionResult.Code.REJECTED;
            }
        }

        // Acquire both sender and target resources
        byte[] sender = ctx.getCaller();
        byte[] target = ctx.getAddress();

        this.resourceMonitor.acquire(sender, task);
        this.resourceMonitor.acquire(target, task);

        // nonce check
        if (!taskTransactionalKernel.accountNonceEquals(sender, ctx.getNonce())) {
            error = TransactionResult.Code.REJECTED_INVALID_NONCE;
        }

        TransactionResult result = null;
        if (null == error) {
            // If this is a GC, we need to handle it specially.  Otherwise, use the common invoke path (handles both CREATE and CALL).
            if (ctx.isGarbageCollectionRequest()) {
                // The GC case operates directly on the top-level KernelInterface.
                // (remember that the "sender" is who we are updating).
                result = runGc(taskTransactionalKernel, sender, ctx);
            } else {
                // The CREATE/CALL case is handled via the common external invoke path.
                result = runExternalInvoke(taskTransactionalKernel, task, ctx);
            }
        } else {
            result = new TransactionResult();
            result.setStatusCode(error);
            result.setEnergyUsed(ctx.getEnergyLimit());
        }

        // Refund energy for transaction
        long energyRemaining = (ctx.getEnergyLimit() - result.getEnergyUsed()) * ctx.getEnergyPrice();
        taskTransactionalKernel.adjustBalance(sender, BigInteger.valueOf(energyRemaining));

        // Task transactional kernel commits are serialized through address resource monitor
        if (!this.resourceMonitor.commitKernelForTask(task, result.getStatusCode().isRejected())){
            result.setStatusCode(TransactionResult.Code.FAILED_ABORT);
        }

        if (TransactionResult.Code.FAILED_ABORT != result.getStatusCode()){
            result.setExternalTransactionalKernel(taskTransactionalKernel);
        }

        return result;
    }

    @Override
    public void setKernel(KernelInterface kernel) {
        if (null != this.backgroundFatalError) {
            throw this.backgroundFatalError;
        }
        this.kernel = kernel;
    }

    @Override
    public void shutdown() {
        RuntimeException failureDuringShutdown = null;
        try {
            this.handoff.stopAndWaitForShutdown();
        } catch (RuntimeException t) {
            // Note that this is usually the same instance as backgroundFatalError can fail for other reasons.  Catch this, complete
            // the shutdown, then re-throw it.
            failureDuringShutdown = t;
        }
        this.handoff = null;
        RuntimeAssertionError.assertTrue(this == AvmImpl.currentAvm);
        AvmImpl.currentAvm = null;
        this.hotCache = null;
        
        // Note that we don't want to hide the background exception, if one happened, but we do want to complete the shutdown, so we do this at the end.
        if (null != failureDuringShutdown) {
            throw failureDuringShutdown;
        }
        if (null != this.backgroundFatalError) {
            throw this.backgroundFatalError;
        }
    }

    @Override
    public TransactionResult runInternalTransaction(KernelInterface parentKernel, TransactionTask task, TransactionContext context) {
        if (null != this.backgroundFatalError) {
            throw this.backgroundFatalError;
        }
        return commonInvoke(parentKernel, task, context);
    }

    private TransactionResult runExternalInvoke(KernelInterface parentKernel, TransactionTask task, TransactionContext ctx) {
        // to capture any error during validation
        TransactionResult.Code error = null;

        // Sanity checks around energy pricing and nonce are done in the caller.
        // balance check
        byte[] sender = ctx.getCaller();

        BigInteger transactionCost = BigInteger.valueOf(ctx.getEnergyLimit() * ctx.getEnergyPrice()).add(ctx.getValue());
        if (!parentKernel.accountBalanceIsAtLeast(sender, transactionCost)) {
            error = TransactionResult.Code.REJECTED_INSUFFICIENT_BALANCE;
        }

        // exit if validation check fails
        if (error != null) {
            TransactionResult result = new TransactionResult();
            result.setStatusCode(error);
            result.setEnergyUsed(ctx.getEnergyLimit());
            return result;
        }

        /*
         * After this point, no rejection should occur.
         */

        // Deduct the total energy cost
        parentKernel.adjustBalance(ctx.getCaller(), BigInteger.valueOf(ctx.getEnergyLimit() * ctx.getEnergyPrice()).negate());

        // Run the common logic with the parent kernel as the top-level one.
        TransactionResult result = commonInvoke(parentKernel, task, ctx);

        // Transfer fees to miner
        parentKernel.adjustBalance(ctx.getBlockCoinbase(), BigInteger.valueOf(result.getEnergyUsed() * ctx.getEnergyPrice()));

        return result;
    }

    private TransactionResult commonInvoke(KernelInterface parentKernel, TransactionTask task, TransactionContext ctx) {
        if (logger.isDebugEnabled()) {
            logger.debug("Transaction: address = {}, caller = {}, value = {}, data = {}, energyLimit = {}",
                    Helpers.bytesToHexString(ctx.getAddress()),
                    Helpers.bytesToHexString(ctx.getCaller()),
                    ctx.getValue(),
                    Helpers.bytesToHexString(ctx.getData()),
                    ctx.getEnergyLimit());
        }
        // We expect that the GC transactions are handled specially, within the caller.
        RuntimeAssertionError.assertTrue(!ctx.isGarbageCollectionRequest());

        // Invoke calls must build their transaction on top of an existing "parent" kernel.
        TransactionalKernel thisTransactionKernel = new TransactionalKernel(parentKernel);

        // only one result (mutable) shall be created per transaction execution
        TransactionResult result = new TransactionResult();
        result.setStatusCode(TransactionResult.Code.SUCCESS);
        result.setEnergyUsed(ctx.getBasicCost()); // basic tx cost

        // conduct value transfer
        thisTransactionKernel.adjustBalance(ctx.getCaller(), ctx.getValue().negate());
        thisTransactionKernel.adjustBalance(ctx.getAddress(), ctx.getValue());

        // At this stage, transaction can no longer be rejected.
        // The nonce increment will be done regardless of the transaction result.
        task.getTaskKernel().incrementNonce(ctx.getCaller());

        // do nothing for balance transfers of which the recipient is not a DApp address.
        if (!(ctx.isBalanceTransfer() &&
                (null == thisTransactionKernel.getCode(ctx.getAddress())
                || (null != thisTransactionKernel.getCode(ctx.getAddress()) && 0 == thisTransactionKernel.getCode(ctx.getAddress()).length)))) {
            if (ctx.isCreate()) { // create
                DAppCreator.create(thisTransactionKernel, this, task, ctx, result);
            } else { // call
                byte[] dappAddress = ctx.getAddress();
                // See if this call is trying to reenter one already on this call-stack.  If so, we will need to partially resume its state.
                ReentrantDAppStack.ReentrantState stateToResume = task.getReentrantDAppStack().tryShareState(dappAddress);

                LoadedDApp dapp;
                // The reentrant cache is obviously the first priority.
                // (note that we also want to check the kernel we were given to make sure that this DApp hasn't been deleted since we put it in the cache.
                if ((null != stateToResume) && (null != thisTransactionKernel.getCode(dappAddress))) {
                    dapp = stateToResume.dApp;
                    // Call directly and don't interact with DApp cache (we are reentering the state, not the origin of it).
                    DAppExecutor.call(thisTransactionKernel, this, dapp, stateToResume, task, ctx, result);
                } else {
                    // If we didn't find it there (that is only for reentrant calls so it is rarely found in the stack), try the hot DApp cache.
                    ByteArrayWrapper addressWrapper = new ByteArrayWrapper(dappAddress);
                    dapp = this.hotCache.checkout(addressWrapper);
                    if (null == dapp) {
                        // If we didn't find it there, just load it.
                        try {
                            dapp = DAppLoader.loadFromGraph(new KeyValueObjectGraph(thisTransactionKernel, dappAddress).getCode());

                            // If the dapp is freshly loaded, we set the block num
                            if (null != dapp){
                                dapp.setLoadedBlockNum(ctx.getBlockNumber());
                            }

                        } catch (IOException e) {
                            unexpected(e); // the jar was created by AVM; IOException is unexpected
                        }
                    }
                    // Run the call and, if successful, check this into the hot DApp cache.
                    if (null != dapp) {
                        DAppExecutor.call(thisTransactionKernel, this, dapp, stateToResume, task, ctx, result);
                        if (TransactionResult.Code.SUCCESS == result.getStatusCode()) {
                            dapp.cleanForCache();
                            this.hotCache.checkin(addressWrapper, dapp);
                        }
                    }
                }
            }
        }

        if (result.getStatusCode().isSuccess()) {
            thisTransactionKernel.commit();
        } else {
            result.clearLogs();
            result.rejectInternalTransactions();
        }

        logger.debug("Result: {}", result);
        return result;
    }

    private TransactionResult runGc(KernelInterface parentKernel, byte[] dappAddress, TransactionContext ctx) {
        RuntimeAssertionError.assertTrue(ctx.isGarbageCollectionRequest());

        ByteArrayWrapper addressWrapper = new ByteArrayWrapper(dappAddress);
        IObjectGraphStore graphStore = new KeyValueObjectGraph(parentKernel, dappAddress);
        
        LoadedDApp dapp = this.hotCache.checkout(addressWrapper);
        if (null == dapp) {
            // If we didn't find it there, just load it.
            try {
                dapp = DAppLoader.loadFromGraph(graphStore.getCode());

                // If the dapp is freshly loaded, we set the block num
                if (null != dapp){
                    dapp.setLoadedBlockNum(ctx.getBlockNumber());
                }

            } catch (IOException e) {
                unexpected(e); // the jar was created by AVM; IOException is unexpected
            }
        }
        
        TransactionResult result = new TransactionResult();
        if (null != dapp) {
            // Run the GC and check this into the hot DApp cache.
            long instancesFreed = graphStore.gc();
            this.hotCache.checkin(addressWrapper, dapp);
            // We want to set this to success and report the energy used as the refund found by the GC.
            // NOTE:  This is the total value of the refund as splitting that between the DApp and node is a higher-level decision.
            long storageEnergyRefund = instancesFreed * InstrumentationBasedStorageFees.DEPOSIT_WRITE_COST;
            result.setStatusCode(TransactionResult.Code.SUCCESS);
            result.setEnergyUsed(-storageEnergyRefund);
        } else {
            // If we failed to find the application, we will currently return this as a generic FAILED_INVALID but we may want a more
            // specific code in the future.
            result.setStatusCode(TransactionResult.Code.FAILED_INVALID);
            result.setEnergyUsed(0);
        }
        return result;
    }

    @Override
    public AddressResourceMonitor getResourceMonitor() {
        if (null != this.backgroundFatalError) {
            throw this.backgroundFatalError;
        }
        return resourceMonitor;
    }

    private void validateCodeCache(long blockNum){
        Predicate<SoftReference<LoadedDApp>> condition = (v) -> null != v.get() && v.get().getLoadedBlockNum() >= blockNum;
        this.hotCache.removeValueIf(condition);
    }
}
