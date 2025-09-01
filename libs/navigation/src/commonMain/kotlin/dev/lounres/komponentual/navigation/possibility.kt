package dev.lounres.komponentual.navigation

import dev.lounres.kone.collections.list.KoneMutableList
import dev.lounres.kone.collections.list.of
import dev.lounres.kone.collections.list.toKoneList
import dev.lounres.kone.collections.set.KoneSet
import dev.lounres.kone.collections.set.empty
import dev.lounres.kone.collections.set.of
import dev.lounres.kone.collections.utils.forEach
import dev.lounres.kone.hub.KoneAsynchronousHub
import dev.lounres.kone.maybe.Maybe
import dev.lounres.kone.maybe.None
import dev.lounres.kone.maybe.Some
import dev.lounres.kone.relations.Equality
import dev.lounres.kone.relations.Hashing
import dev.lounres.kone.relations.Order
import dev.lounres.kone.relations.defaultFor
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope


public typealias PossibilityNavigationEvent<Configuration> = (Maybe<Configuration>) -> Maybe<Configuration>

public typealias PossibilityNavigationSource<Configuration> = NavigationSource<PossibilityNavigationEvent<Configuration>>

public fun interface PossibilityNavigationTarget<Configuration> {
    public suspend fun navigate(possibilityTransformation: PossibilityNavigationEvent<Configuration>)
}

public suspend fun <Configuration> PossibilityNavigationTarget<in Configuration>.set(configuration: Maybe<Configuration>) {
    navigate { configuration }
}

public suspend fun <Configuration> PossibilityNavigationTarget<in Configuration>.set(configuration: Configuration) {
    navigate { Some(configuration) }
}

public suspend fun PossibilityNavigationTarget<*>.clear() {
    navigate { None }
}

public interface PossibilityNavigationHub<Configuration> : PossibilityNavigationSource<Configuration>, PossibilityNavigationTarget<Configuration>

public fun <Configuration> PossibilityNavigationHub(): PossibilityNavigationHub<Configuration> = PossibilityNavigationHubImpl()

internal class PossibilityNavigationHubImpl<Configuration> : PossibilityNavigationHub<Configuration> {
    private val callbacksLock = ReentrantLock()
    private val callbacks: KoneMutableList<suspend (PossibilityNavigationEvent<Configuration>) -> Unit> = KoneMutableList.of()
    
    override fun subscribe(observer: suspend (PossibilityNavigationEvent<Configuration>) -> Unit) {
        callbacksLock.withLock {
            callbacks.add(observer)
        }
    }
    
    override suspend fun navigate(possibilityTransformation: PossibilityNavigationEvent<Configuration>) {
        val callbacksToLaunch = callbacksLock.withLock {
            callbacks.toKoneList()
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

public suspend fun <
    Configuration,
    Child,
> childrenPossibility(
    configurationEquality: Equality<Configuration> = Equality.defaultFor(),
    configurationHashing: Hashing<Configuration>? = null,
    configurationOrder: Order<Configuration>? = null,
    source: PossibilityNavigationSource<Configuration>,
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