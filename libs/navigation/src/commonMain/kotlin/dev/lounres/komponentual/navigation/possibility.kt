package dev.lounres.komponentual.navigation

import dev.lounres.kone.collections.interop.toList
import dev.lounres.kone.collections.list.KoneMutableList
import dev.lounres.kone.collections.list.of
import dev.lounres.kone.collections.set.KoneSet
import dev.lounres.kone.collections.set.empty
import dev.lounres.kone.collections.set.of
import dev.lounres.kone.hub.KoneAsynchronousHub
import dev.lounres.kone.maybe.Maybe
import dev.lounres.kone.maybe.None
import dev.lounres.kone.maybe.Some
import dev.lounres.kone.relations.Equality
import dev.lounres.kone.relations.Hashing
import dev.lounres.kone.relations.Order
import dev.lounres.kone.relations.defaultEquality
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope


public typealias PossibilityNavigationEvent<Configuration> = (Maybe<Configuration>) -> Maybe<Configuration>

public typealias PossibilityNavigation<Configuration> = NavigationSource<PossibilityNavigationEvent<Configuration>>

public interface MutablePossibilityNavigation<Configuration> : PossibilityNavigation<Configuration> {
    public suspend fun navigate(possibilityTransformation: PossibilityNavigationEvent<Configuration>)
}

public fun <Configuration> MutablePossibilityNavigation(): MutablePossibilityNavigation<Configuration> =
    MutablePossibilityNavigationImpl()

public suspend fun <Configuration> MutablePossibilityNavigation<Configuration>.set(configuration: Configuration) {
    navigate { Some(configuration) }
}

public suspend fun <Configuration> MutablePossibilityNavigation<Configuration>.clear() {
    navigate { None }
}

internal class MutablePossibilityNavigationImpl<Configuration>(
) : MutablePossibilityNavigation<Configuration> {
    private val callbacksLock = ReentrantLock()
    private val callbacks: KoneMutableList<suspend (PossibilityNavigationEvent<Configuration>) -> Unit> = KoneMutableList.of()
    
    override fun subscribe(observer: suspend (PossibilityNavigationEvent<Configuration>) -> Unit) {
        callbacksLock.withLock {
            callbacks.add(observer)
        }
    }
    
    override suspend fun navigate(possibilityTransformation: PossibilityNavigationEvent<Configuration>) {
        val callbacksToLaunch = callbacksLock.withLock {
            callbacks.toList()
        }
        supervisorScope {
            callbacksToLaunch.forEach { callback ->
                launch {
                    callback(possibilityTransformation)
                }
            }
        }
    }
}

public typealias PossibilityNavigationState<Configuration> = Maybe<Configuration>

public typealias ChildrenPossibility<Configuration, Component> = Maybe<ChildWithConfiguration<Configuration, Component>>

public suspend fun <
    Configuration,
    Child,
> childrenPossibility(
    configurationEquality: Equality<Configuration> = defaultEquality(),
    configurationHashing: Hashing<Configuration>? = null,
    configurationOrder: Order<Configuration>? = null,
    source: PossibilityNavigation<Configuration>,
    initialConfiguration: Maybe<Configuration>,
    createChild: suspend (configuration: Configuration, nextState: PossibilityNavigationState<Configuration>) -> Child,
    destroyChild: suspend (configuration: Configuration, data: Child, nextState: PossibilityNavigationState<Configuration>) -> Unit,
    updateChild: suspend (configuration: Configuration, data: Child, nextState: PossibilityNavigationState<Configuration>) -> Unit,
): KoneAsynchronousHub<NavigationResult<PossibilityNavigationState<Configuration>, Configuration, Child>> =
    children(
        configurationEquality = configurationEquality,
        configurationHashing = configurationHashing,
        configurationOrder = configurationOrder,
        source = source,
        initialState = initialConfiguration,
        stateConfigurationsMapping = { currentNavigationState ->
            when (currentNavigationState) {
                None -> KoneSet.empty()
                is Some<Configuration> -> KoneSet.of(
                    currentNavigationState.value,
                    elementEquality = configurationEquality,
                    elementHashing = configurationHashing,
                    elementOrder = configurationOrder,
                )
            }
        },
        navigationTransition = { previousState, event -> event(previousState) },
        createChild = createChild,
        destroyChild = destroyChild,
        updateChild = updateChild,
    )