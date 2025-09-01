package dev.lounres.komponentual.navigation

import dev.lounres.kone.collections.list.KoneMutableList
import dev.lounres.kone.collections.list.of
import dev.lounres.kone.collections.list.toKoneList
import dev.lounres.kone.collections.set.KoneSet
import dev.lounres.kone.collections.set.of
import dev.lounres.kone.collections.utils.forEach
import dev.lounres.kone.hub.KoneAsynchronousHub
import dev.lounres.kone.relations.Equality
import dev.lounres.kone.relations.Hashing
import dev.lounres.kone.relations.Order
import dev.lounres.kone.relations.defaultFor
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope


public typealias SlotNavigationEvent<Configuration> = (Configuration) -> Configuration

public typealias SlotNavigationSource<Configuration> = NavigationSource<SlotNavigationEvent<Configuration>>

public fun interface SlotNavigationTarget<Configuration> {
    public suspend fun navigate(slotTransformation: SlotNavigationEvent<Configuration>)
}

public suspend fun <Configuration> SlotNavigationTarget<in Configuration>.set(configuration: Configuration) {
    navigate { configuration }
}

public interface SlotNavigationHub<Configuration> : SlotNavigationSource<Configuration>, SlotNavigationTarget<Configuration>

public fun <Configuration> SlotNavigationHub(): SlotNavigationHub<Configuration> =
    SlotNavigationHubImpl()

internal class SlotNavigationHubImpl<Configuration>: SlotNavigationHub<Configuration> {
    private val callbacksLock = ReentrantLock()
    private val callbacks: KoneMutableList<suspend (SlotNavigationEvent<Configuration>) -> Unit> = KoneMutableList.of()
    
    override fun subscribe(observer: suspend (SlotNavigationEvent<Configuration>) -> Unit) {
        callbacksLock.withLock {
            callbacks.add(observer)
        }
    }
    
    override suspend fun navigate(slotTransformation: SlotNavigationEvent<Configuration>) {
        val callbacksToLaunch = callbacksLock.withLock {
            callbacks.toKoneList()
        }
        supervisorScope {
            callbacksToLaunch.forEach { callback ->
                launch {
                    callback(slotTransformation)
                }
            }
        }
    }
}

public suspend fun <
    Configuration,
    Child,
> childrenSlot(
    configurationEquality: Equality<Configuration> = Equality.defaultFor(),
    configurationHashing: Hashing<Configuration>? = null,
    configurationOrder: Order<Configuration>? = null,
    source: SlotNavigationSource<Configuration>,
    initialConfiguration: Configuration,
    createChild: suspend (configuration: Configuration, nextState: Configuration) -> Child,
    destroyChild: suspend (configuration: Configuration, data: Child, nextState: Configuration) -> Unit,
    updateChild: suspend (configuration: Configuration, data: Child, nextState: Configuration) -> Unit,
): KoneAsynchronousHub<NavigationResult<Configuration, Configuration, Child>> =
    children(
        configurationEquality = configurationEquality,
        configurationHashing = configurationHashing,
        configurationOrder = configurationOrder,
        source = source,
        initialState = initialConfiguration,
        stateConfigurationsMapping = { currentNavigationState ->
            KoneSet.of(
                currentNavigationState,
                elementEquality = configurationEquality,
                elementHashing = configurationHashing,
                elementOrder = configurationOrder,
            )
        },
        navigationTransition = { previousState, event -> event(previousState) },
        createChild = createChild,
        destroyChild = destroyChild,
        updateChild = updateChild,
    )