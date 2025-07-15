package dev.lounres.komponentual.navigation

import dev.lounres.kone.collections.interop.toList
import dev.lounres.kone.collections.list.KoneMutableList
import dev.lounres.kone.collections.list.of
import dev.lounres.kone.collections.set.KoneSet
import dev.lounres.kone.collections.set.of
import dev.lounres.kone.hub.KoneAsynchronousHub
import dev.lounres.kone.relations.Equality
import dev.lounres.kone.relations.Hashing
import dev.lounres.kone.relations.Order
import dev.lounres.kone.relations.defaultEquality
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope


public typealias SlotNavigationEvent<Configuration> = (Configuration) -> Configuration

public typealias SlotNavigation<Configuration> = NavigationSource<SlotNavigationEvent<Configuration>>

public interface MutableSlotNavigation<Configuration> : SlotNavigation<Configuration> {
    public suspend fun navigate(slotTransformation: SlotNavigationEvent<Configuration>)
}

public fun <Configuration> MutableSlotNavigation(): MutableSlotNavigation<Configuration> =
    MutableSlotNavigationImpl()

public suspend fun <Configuration> MutableSlotNavigation<Configuration>.set(configuration: Configuration) {
    navigate { configuration }
}

internal class MutableSlotNavigationImpl<Configuration>(
) : MutableSlotNavigation<Configuration> {
    private val callbacksLock = ReentrantLock()
    private val callbacks: KoneMutableList<suspend (SlotNavigationEvent<Configuration>) -> Unit> = KoneMutableList.of()
    
    override fun subscribe(observer: suspend (SlotNavigationEvent<Configuration>) -> Unit) {
        callbacksLock.withLock {
            callbacks.add(observer)
        }
    }
    
    override suspend fun navigate(slotTransformation: SlotNavigationEvent<Configuration>) {
        val callbacksToLaunch = callbacksLock.withLock {
            callbacks.toList()
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

public typealias ChildrenSlot<Configuration, Component> = ChildWithConfiguration<Configuration, Component>

public suspend fun <
    Configuration,
    Child,
> childrenSlot(
    configurationEquality: Equality<Configuration> = defaultEquality(),
    configurationHashing: Hashing<Configuration>? = null,
    configurationOrder: Order<Configuration>? = null,
    source: SlotNavigation<Configuration>,
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