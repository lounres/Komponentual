package dev.lounres.komponentual.lifecycle

import dev.lounres.kone.automata.AsynchronousAutomaton
import dev.lounres.kone.automata.CheckResult
import dev.lounres.kone.automata.SuspendAutomaton
import dev.lounres.kone.automata.move
import dev.lounres.kone.collections.interop.toList
import dev.lounres.kone.collections.iterables.next
import dev.lounres.kone.collections.list.KoneList
import dev.lounres.kone.collections.list.KoneMutableNoddedList
import dev.lounres.kone.collections.list.implementations.KoneGCLinkedList
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


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

public interface Lifecycle<out State, out Transition> {
    public val state: State
    
    public fun subscribe(callback: suspend (Transition) -> Unit): Subscription<State>
    
    public interface Subscription<out State> {
        public val initialState: State
        public fun cancel()
    }
    
    public companion object {
        public inline fun <State> Subscription(
            initialState: State,
            crossinline cancel: () -> Unit,
        ): Subscription<State> = object : Subscription<State> {
            override val initialState: State = initialState
            override fun cancel() { cancel() }
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

private class MutableLifecycleImpl<State, Transition>(
    initialState: State,
    checkNextState: (previousState: State, nextState: State) -> Boolean,
    decomposeTransition: (previousState: State, nextState: State) -> KoneList<Transition>,
    ) : MutableLifecycle<State, Transition> {
    private val callbacksLock = ReentrantLock()
    private var callbacksState: State = initialState
    private val callbacks: KoneMutableNoddedList<suspend (Transition) -> Unit> = KoneGCLinkedList() // TODO: Replace with concurrent queue
    
    private val automaton =
        AsynchronousAutomaton<State, State, Nothing?>(
            initialState = initialState,
            checkTransition = { previousState, nextState ->
                if (checkNextState(previousState, nextState)) CheckResult.Success(nextState) else CheckResult.Failure(null)
            },
            onTransition = { previousState, _, nextState ->
                for (transition in decomposeTransition(previousState, nextState)) {
                    val callbacksToLaunch = callbacksLock.withLock {
                        callbacksState = nextState
                        callbacks.toList()
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
    override fun subscribe(callback: suspend (Transition) -> Unit): Lifecycle.Subscription<State> =
        callbacksLock.withLock {
            val node = callbacks.addNode(callback)
            Lifecycle.Subscription(callbacksState) {
                callbacksLock.withLock {
                    node.remove()
                }
            }
        }
    
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
private class ChildDeferringLifecycle<IState, ITransition, TState, OState, OTransition>(
    private val lifecycle: Lifecycle<IState, ITransition>,
    initialState: TState,
    private val mapState: (IState) -> TState,
    private val mapTransition: (TState, ITransition) -> TState,
    checkNextState: (previousState: TState, nextState: TState) -> Boolean,
    decomposeTransition: (previousState: TState, nextState: TState) -> KoneList<OTransition>,
    private val outputState: (TState) -> OState,
) : DeferredLifecycle<OState, OTransition> {
    private val callbacksLock = ReentrantLock()
    private var callbacksState: OState = outputState(initialState)
    private val callbacks: KoneMutableNoddedList<suspend (OTransition) -> Unit> = KoneGCLinkedList() // TODO: Replace with concurrent queue
    
    private val automatonMutex = Mutex()
    private val automaton =
        SuspendAutomaton<TState, TState, Nothing?>(
            initialState = initialState,
            checkTransition = { previousState, nextState ->
                if (checkNextState(previousState, nextState)) CheckResult.Success(nextState) else CheckResult.Failure(null)
            },
            onTransition = { previousState, _, nextState ->
                for (transition in decomposeTransition(previousState, nextState)) {
                    val callbacksToLaunch = callbacksLock.withLock {
                        callbacksState = outputState(nextState)
                        callbacks.toList()
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
    
    override fun subscribe(callback: suspend (OTransition) -> Unit): Lifecycle.Subscription<OState> =
        callbacksLock.withLock {
            val node = callbacks.addNode(callback)
            Lifecycle.Subscription(callbacksState) {
                callbacksLock.withLock {
                    node.remove()
                }
            }
        }
    
    override suspend fun launch() {
        automatonMutex.withLock {
            val subscription1 = lifecycle.subscribe { transition ->
                automatonMutex.withLock {
                    automaton.move { currentState -> mapTransition(currentState, transition) }
                }
            }
            
            automaton.move(mapState(subscription1.initialState))
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
    private val callbacksLock = ReentrantLock()
    private var callbacksState: OState = outputState(initialState)
    private val callbacks: KoneMutableNoddedList<suspend (OTransition) -> Unit> = KoneGCLinkedList() // TODO: Replace with concurrent queue
    
    private val automatonMutex = Mutex()
    private val automaton =
        SuspendAutomaton<TState, TState, Nothing?>(
            initialState = initialState,
            checkTransition = { previousState, nextState ->
                if (checkNextState(previousState, nextState)) CheckResult.Success(nextState) else CheckResult.Failure(null)
            },
            onTransition = { previousState, _, nextState ->
                for (transition in decomposeTransition(previousState, nextState)) {
                    val callbacksToLaunch = callbacksLock.withLock {
                        callbacksState = outputState(nextState)
                        callbacks.toList()
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
    
    override fun subscribe(callback: suspend (OTransition) -> Unit): Lifecycle.Subscription<OState> =
        callbacksLock.withLock {
            val node = callbacks.addNode(callback)
            Lifecycle.Subscription(callbacksState) {
                callbacksLock.withLock {
                    node.remove()
                }
            }
        }
    
    override suspend fun launch() {
        automatonMutex.withLock {
            val subscription1 = lifecycle1.subscribe { transition ->
                automatonMutex.withLock {
                    automaton.move { currentState -> mapTransition1(currentState, transition) }
                }
            }
            val subscription2 = lifecycle2.subscribe { transition ->
                automatonMutex.withLock {
                    automaton.move { currentState -> mapTransition2(currentState, transition) }
                }
            }
            
            automaton.move(mergeStates(subscription1.initialState, subscription2.initialState))
        }
    }
}