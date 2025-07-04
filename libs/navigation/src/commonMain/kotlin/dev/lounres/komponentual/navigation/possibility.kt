package dev.lounres.komponentual.navigation

import dev.lounres.kone.collections.interop.toList
import dev.lounres.kone.collections.list.KoneMutableList
import dev.lounres.kone.collections.list.of
import dev.lounres.kone.collections.map.get
import dev.lounres.kone.collections.set.KoneSet
import dev.lounres.kone.collections.set.of
import dev.lounres.kone.maybe.Maybe
import dev.lounres.kone.maybe.None
import dev.lounres.kone.maybe.Some
import dev.lounres.kone.maybe.map
import dev.lounres.kone.relations.Equality
import dev.lounres.kone.relations.Hashing
import dev.lounres.kone.relations.Order
import dev.lounres.kone.relations.defaultEquality
import dev.lounres.kone.state.KoneAsynchronousState
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch


public typealias PossibilityNavigationEvent<Configuration> = (Maybe<Configuration>) -> Maybe<Configuration>

public typealias PossibilityNavigation<Configuration> = NavigationSource<PossibilityNavigationEvent<Configuration>>

public interface MutablePossibilityNavigation<Configuration> : PossibilityNavigation<Configuration> {
    public suspend fun navigate(possibilityTransformation: PossibilityNavigationEvent<Configuration>)
}

public fun <Configuration> MutablePossibilityNavigation(coroutineScope: CoroutineScope): MutablePossibilityNavigation<Configuration> =
    MutablePossibilityNavigationImpl(coroutineScope = coroutineScope)

public suspend fun <Configuration> MutablePossibilityNavigation<Configuration>.set(configuration: Configuration) {
    navigate { Some(configuration) }
}

public suspend fun <Configuration> MutablePossibilityNavigation<Configuration>.clear() {
    navigate { None }
}

internal class MutablePossibilityNavigationImpl<Configuration>(
    private val coroutineScope: CoroutineScope,
) : MutablePossibilityNavigation<Configuration> {
    private val callbacksLock = ReentrantLock()
    private val callbacks: KoneMutableList<suspend (PossibilityNavigationEvent<Configuration>) -> Unit> = KoneMutableList.of()
    
    override fun subscribe(observer: suspend (PossibilityNavigationEvent<Configuration>) -> Unit) {
        callbacksLock.withLock {
            callbacks.add(observer)
        }
    }
    
    override suspend fun navigate(possibilityTransformation: PossibilityNavigationEvent<Configuration>) {
        callbacksLock.withLock {
            callbacks.toList()
        }.map { coroutineScope.launch { it(possibilityTransformation) } }.joinAll()
    }
}

public class InnerPossibilityNavigationState<Configuration> internal constructor(
    public val current: Maybe<Configuration>,
    configurationEquality: Equality<Configuration> = defaultEquality(),
    configurationHashing: Hashing<Configuration>? = null,
    configurationOrder: Order<Configuration>? = null,
) : NavigationState<Configuration> {
    override val configurations: KoneSet<Configuration> =
        when (current) {
            None -> KoneSet.of(
                elementEquality = configurationEquality,
                elementHashing = configurationHashing,
                elementOrder = configurationOrder,
            )
            is Some<Configuration> -> KoneSet.of(
                current.value,
                elementEquality = configurationEquality,
                elementHashing = configurationHashing,
                elementOrder = configurationOrder,
            )
        }
}

public typealias ChildrenPossibility<Configuration, Component> = Maybe<ChildWithConfiguration<Configuration, Component>>

public suspend fun <
    Configuration,
    Child,
    Component,
> childrenPossibility(
    configurationEquality: Equality<Configuration> = defaultEquality(),
    configurationHashing: Hashing<Configuration>? = null,
    configurationOrder: Order<Configuration>? = null,
    source: PossibilityNavigation<Configuration>,
    initialConfiguration: Maybe<Configuration>,
    createChild: suspend (configuration: Configuration, nextState: InnerPossibilityNavigationState<Configuration>) -> Child,
    destroyChild: suspend (Child) -> Unit,
    updateChild: suspend (configuration: Configuration, data: Child, nextState: InnerPossibilityNavigationState<Configuration>) -> Unit,
    componentAccessor: suspend (Child) -> Component,
): KoneAsynchronousState<ChildrenPossibility<Configuration, Component>> =
    children(
        configurationEquality = configurationEquality,
        configurationHashing = configurationHashing,
        configurationOrder = configurationOrder,
        source = source,
        initialState = InnerPossibilityNavigationState(
            current = initialConfiguration
        ),
        navigationTransition = { previousState, event ->
            InnerPossibilityNavigationState(
                current = event(previousState.current)
            )
        },
        createChild = createChild,
        destroyChild = destroyChild,
        updateChild = updateChild,
        publicNavigationStateMapper = { innerState, childByConfiguration ->
            innerState.current.map { ChildWithConfiguration(it, componentAccessor(childByConfiguration[it])) }
        },
    )