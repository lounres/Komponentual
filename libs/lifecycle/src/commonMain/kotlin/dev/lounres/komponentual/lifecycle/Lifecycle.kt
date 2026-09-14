package dev.lounres.komponentual.lifecycle

import dev.lounres.kone.automata.AsynchronousAutomaton
import dev.lounres.kone.automata.CheckResult
import dev.lounres.kone.automata.SuspendAutomaton
import dev.lounres.kone.automata.move
import dev.lounres.kone.collections.iterator.next
import dev.lounres.kone.collections.list.KoneList
import dev.lounres.kone.collections.list.KoneMutableList
import dev.lounres.kone.collections.list.KoneMutableNoddedList
import dev.lounres.kone.collections.list.implementations.KoneArrayGrowableList
import dev.lounres.kone.collections.list.implementations.KoneGCLinkedSizedList
import dev.lounres.kone.collections.list.toKoneList
import dev.lounres.kone.collections.utils.forEach
import dev.lounres.kone.contexts.invoke
import dev.lounres.kone.relations.Equality
import dev.lounres.kone.relations.absoluteFor
import dev.lounres.kone.relations.eq
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.jvm.JvmInline


@RequiresOptIn(
    message = "",
    level = RequiresOptIn.Level.ERROR,
)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.ANNOTATION_CLASS,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
    AnnotationTarget.TYPEALIAS,
)
public annotation class DelicateLifecycleAPI

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CLASS)
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "This is primitive internal lifecycle API. Use with caution."
)
public annotation class InternalLifecycleApi

public interface Lifecycle<out State, out Transition> {
    public val state: State
    @InternalLifecycleApi
    public val callbacksState: State
    
    @IgnorableReturnValue
    public fun subscribe(callback: suspend (Transition) -> Unit): Subscription
    
    @InternalLifecycleApi
    public fun lockCallbacksState()
    @InternalLifecycleApi
    public fun unlockCallbacksState()
    
    public fun interface Subscription {
        public fun cancel()
    }
    
    public companion object
}

@JvmInline
public value class LifecycleBlockingSubscriptionScope<out State, out Transition> @PublishedApi internal constructor(private val hub: Lifecycle<State, Transition>) {
    @IgnorableReturnValue
    public fun subscribe(callback: suspend (Transition) -> Unit): Lifecycle.Subscription = hub.subscribe(callback)
}

@OptIn(InternalLifecycleApi::class)
public inline fun <State, Transition, Result> Lifecycle<State, Transition>.buildSubscriptionLocking(builder: LifecycleBlockingSubscriptionScope<State, Transition>.(State) -> Result): Result {
    lockCallbacksState()
    val result = try {
        LifecycleBlockingSubscriptionScope(this).builder(callbacksState)
    } finally {
        unlockCallbacksState()
    }
    return result
}

public class LifecycleAtomicSubscriptionScope<out State, out Transition> @PublishedApi internal constructor(private val hub: Lifecycle<State, Transition>) {
    @PublishedApi
    internal val subscriptions: KoneMutableList<Lifecycle.Subscription> = KoneArrayGrowableList()
    @IgnorableReturnValue
    public fun subscribe(callback: suspend (Transition) -> Unit): Lifecycle.Subscription =
        hub.subscribe(callback).also { subscriptions.add(it) }
}

@OptIn(InternalLifecycleApi::class)
public inline fun <State, Transition, Result> Lifecycle<State, Transition>.buildSubscriptionAtomic(stateEquality: Equality<State> = Equality.absoluteFor(), builder: LifecycleAtomicSubscriptionScope<State, Transition>.(State) -> Result): Result {
    while (true) {
        val callbacksState = this.callbacksState
        val scope = LifecycleAtomicSubscriptionScope(this)
        val result = try {
            scope.builder(callbacksState)
        } catch (throwable: Throwable) {
            scope.subscriptions.forEach { it.cancel() }
            throw throwable
        }
        if (stateEquality { callbacksState eq this.callbacksState }) return result
        else {
            scope.subscriptions.forEach { it.cancel() }
        }
    }
}

public interface MutableLifecycle<State, out Transition> : Lifecycle<State, Transition> {
    public suspend fun moveTo(state: State)
}

public fun <State, Transition> MutableLifecycle(
    initialState: State,
    checkNextState: (previousState: State, nextState: State) -> Boolean,
    decomposeTransition: (previousState: State, nextState: State) -> KoneList<Transition>,
): MutableLifecycle<State, Transition> =
    MutableLifecycleImpl(
        initialState = initialState,
        checkNextState = checkNextState,
        decomposeTransition = decomposeTransition,
    )

@OptIn(InternalLifecycleApi::class)
private class MutableLifecycleImpl<State, Transition>(
    initialState: State,
    checkNextState: (previousState: State, nextState: State) -> Boolean,
    decomposeTransition: (previousState: State, nextState: State) -> KoneList<Transition>,
) : MutableLifecycle<State, Transition> {
    override var callbacksState: State = initialState
    private val callbacksStateLock = ReentrantLock()
    private val callbacks: KoneMutableNoddedList<suspend (Transition) -> Unit> = KoneGCLinkedSizedList()
    private val callbacksLock: ReentrantLock = ReentrantLock()
    
    override fun subscribe(callback: suspend (Transition) -> Unit): Lifecycle.Subscription {
        callbacksLock.withLock {
            val node = callbacks.addNode(callback)
            return Lifecycle.Subscription {
                callbacksLock.withLock {
                    node.remove()
                }
            }
        }
    }
    
    override fun lockCallbacksState() {
        callbacksStateLock.lock()
    }
    
    override fun unlockCallbacksState() {
        callbacksStateLock.unlock()
    }
    
    private val automaton =
        AsynchronousAutomaton<State, State, Nothing?>(
            initialState = initialState,
            checkTransition = { previousState, nextState ->
                if (checkNextState(previousState, nextState)) CheckResult.Success(nextState) else CheckResult.Failure(null)
            },
            onTransition = { previousState, _, nextState ->
                for (transition in decomposeTransition(previousState, nextState)) {
                    val callbacksToLaunch = callbacksStateLock.withLock {
                        callbacksState = nextState
                        callbacksLock.withLock { callbacks.toKoneList() }
                    }
                    supervisorScope {
                        callbacksToLaunch.forEach { callback ->
                            launch { callback(transition) }
                        }
                    }
                }
            },
        )
    
    override val state: State get() = automaton.state
    
    override suspend fun moveTo(state: State) {
        automaton.move(state)
    }
}

@DelicateLifecycleAPI
public interface DeferredLifecycle<out State, out Transition> : Lifecycle<State, Transition> {
    public suspend fun launch()
}

@DelicateLifecycleAPI
public fun <IState, ITransition, TState, OState, OTransition> Lifecycle<IState, ITransition>.childDeferring(
    initialState: TState,
    mapState: (IState) -> TState,
    mapTransition: (TState, ITransition) -> TState,
    checkNextState: (previousState: TState, nextState: TState) -> Boolean,
    decomposeTransition: (previousState: TState, nextState: TState) -> KoneList<OTransition>,
    outputState: (TState) -> OState,
): DeferredLifecycle<OState, OTransition> =
    ChildDeferringLifecycle(
        lifecycle = this,
        initialState = initialState,
        mapState = mapState,
        mapTransition = mapTransition,
        checkNextState = checkNextState,
        decomposeTransition = decomposeTransition,
        outputState = outputState,
    )

@DelicateLifecycleAPI
@OptIn(InternalLifecycleApi::class)
private class ChildDeferringLifecycle<IState, ITransition, TState, OState, OTransition>(
    private val lifecycle: Lifecycle<IState, ITransition>,
    initialState: TState,
    private val mapState: (IState) -> TState,
    private val mapTransition: (TState, ITransition) -> TState,
    checkNextState: (previousState: TState, nextState: TState) -> Boolean,
    decomposeTransition: (previousState: TState, nextState: TState) -> KoneList<OTransition>,
    private val outputState: (TState) -> OState,
) : DeferredLifecycle<OState, OTransition> {
    override var callbacksState: OState = outputState(initialState)
    private val callbacksStateLock = ReentrantLock()
    private val callbacks: KoneMutableNoddedList<suspend (OTransition) -> Unit> = KoneGCLinkedSizedList()
    private val callbacksLock: ReentrantLock = ReentrantLock()
    
    override fun subscribe(callback: suspend (OTransition) -> Unit): Lifecycle.Subscription {
        callbacksLock.withLock {
            val node = callbacks.addNode(callback)
            return Lifecycle.Subscription {
                callbacksLock.withLock {
                    node.remove()
                }
            }
        }
    }
    
    override fun lockCallbacksState() {
        callbacksStateLock.lock()
    }
    
    override fun unlockCallbacksState() {
        callbacksStateLock.unlock()
    }
    
    private val automatonMutex = Mutex()
    private val automaton =
        SuspendAutomaton<TState, TState, Nothing?>(
            initialState = initialState,
            checkTransition = { previousState, nextState ->
                if (checkNextState(previousState, nextState)) CheckResult.Success(nextState) else CheckResult.Failure(null)
            },
            onTransition = { previousState, _, nextState ->
                for (transition in decomposeTransition(previousState, nextState)) {
                    val callbacksToLaunch = callbacksStateLock.withLock {
                        callbacksState = outputState(nextState)
                        callbacksLock.withLock { callbacks.toKoneList() }
                    }
                    supervisorScope {
                        callbacksToLaunch.forEach { callback ->
                            launch { callback(transition) }
                        }
                    }
                }
            },
        )
    
    override val state: OState get() = outputState(automaton.state)
    
    override suspend fun launch() {
        automatonMutex.withLock {
            lifecycle.buildSubscriptionLocking { initialState ->
                automaton.move(mapState(initialState))
                subscribe { transition ->
                    automatonMutex.withLock {
                        automaton.move { currentState -> mapTransition(currentState, transition) }
                    }
                }
            }
        }
    }
}

@DelicateLifecycleAPI
public fun <I1State, I1Transition, I2State, I2Transition, TState, OState, OTransition> Lifecycle.Companion.mergeDeferring(
    lifecycle1: Lifecycle<I1State, I1Transition>,
    lifecycle2: Lifecycle<I2State, I2Transition>,
    initialState: TState,
    mergeStates: (I1State, I2State) -> TState,
    mapTransition1: (TState, I1Transition) -> TState,
    mapTransition2: (TState, I2Transition) -> TState,
    checkNextState: (previousState: TState, nextState: TState) -> Boolean,
    decomposeTransition: (previousState: TState, nextState: TState) -> KoneList<OTransition>,
    outputState: (TState) -> OState,
): DeferredLifecycle<OState, OTransition> =
    MergeDeferringLifecycle(
        lifecycle1 = lifecycle1,
        lifecycle2 = lifecycle2,
        initialState = initialState,
        mergeStates = mergeStates,
        mapTransition1 = mapTransition1,
        mapTransition2 = mapTransition2,
        checkNextState = checkNextState,
        decomposeTransition = decomposeTransition,
        outputState = outputState,
    )

@DelicateLifecycleAPI
@OptIn(InternalLifecycleApi::class)
private class MergeDeferringLifecycle<I1State, I1Transition, I2State, I2Transition, TState, OState, OTransition>(
    private val lifecycle1: Lifecycle<I1State, I1Transition>,
    private val lifecycle2: Lifecycle<I2State, I2Transition>,
    initialState: TState,
    private val mergeStates: (I1State, I2State) -> TState,
    private val mapTransition1: (TState, I1Transition) -> TState,
    private val mapTransition2: (TState, I2Transition) -> TState,
    checkNextState: (previousState: TState, nextState: TState) -> Boolean,
    decomposeTransition: (previousState: TState, nextState: TState) -> KoneList<OTransition>,
    private val outputState: (TState) -> OState,
) : DeferredLifecycle<OState, OTransition> {
    override var callbacksState: OState = outputState(initialState)
    private val callbacksStateLock = ReentrantLock()
    private val callbacks: KoneMutableNoddedList<suspend (OTransition) -> Unit> = KoneGCLinkedSizedList()
    private val callbacksLock: ReentrantLock = ReentrantLock()
    
    override fun subscribe(callback: suspend (OTransition) -> Unit): Lifecycle.Subscription {
        callbacksLock.withLock {
            val node = callbacks.addNode(callback)
            return Lifecycle.Subscription {
                callbacksLock.withLock {
                    node.remove()
                }
            }
        }
    }
    
    override fun lockCallbacksState() {
        callbacksStateLock.lock()
    }
    
    override fun unlockCallbacksState() {
        callbacksStateLock.unlock()
    }
    
    private val automatonMutex = Mutex()
    private val automaton =
        SuspendAutomaton<TState, TState, Nothing?>(
            initialState = initialState,
            checkTransition = { previousState, nextState ->
                if (checkNextState(previousState, nextState)) CheckResult.Success(nextState) else CheckResult.Failure(null)
            },
            onTransition = { previousState, _, nextState ->
                for (transition in decomposeTransition(previousState, nextState)) {
                    val callbacksToLaunch = callbacksStateLock.withLock {
                        callbacksState = outputState(nextState)
                        callbacksLock.withLock { callbacks.toKoneList() }
                    }
                    supervisorScope {
                        callbacksToLaunch.forEach { callback ->
                            launch { callback(transition) }
                        }
                    }
                }
            },
        )
    
    override val state: OState get() = outputState(automaton.state)
    
    override suspend fun launch() {
        automatonMutex.withLock {
            val initialState1 = lifecycle1.buildSubscriptionAtomic { initialState ->
                subscribe { transition ->
                    automatonMutex.withLock {
                        automaton.move { currentState -> mapTransition1(currentState, transition) }
                    }
                }
                initialState
            }
            val initialState2 = lifecycle2.buildSubscriptionAtomic { initialState ->
                subscribe { transition ->
                    automatonMutex.withLock {
                        automaton.move { currentState -> mapTransition2(currentState, transition) }
                    }
                }
                initialState
            }
            
            automaton.move(mergeStates(initialState1, initialState2))
        }
    }
}