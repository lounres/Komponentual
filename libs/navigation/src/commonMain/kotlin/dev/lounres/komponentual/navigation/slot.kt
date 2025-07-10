package dev.lounres.komponentual.navigation

import dev.lounres.kone.collections.interop.toList
import dev.lounres.kone.collections.list.KoneMutableList
import dev.lounres.kone.collections.list.of
import dev.lounres.kone.collections.map.get
import dev.lounres.kone.collections.set.KoneSet
import dev.lounres.kone.collections.set.of
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


public typealias SlotNavigationEvent<Configuration> = (Configuration) -> Configuration

public typealias SlotNavigation<Configuration> = NavigationSource<SlotNavigationEvent<Configuration>>

public interface MutableSlotNavigation<Configuration> : SlotNavigation<Configuration> {
    public suspend fun navigate(slotTransformation: SlotNavigationEvent<Configuration>)
}

public fun <Configuration> MutableSlotNavigation(coroutineScope: CoroutineScope): MutableSlotNavigation<Configuration> =
    MutableSlotNavigationImpl(coroutineScope = coroutineScope)

public suspend fun <Configuration> MutableSlotNavigation<Configuration>.set(configuration: Configuration) {
    navigate { configuration }
}

internal class MutableSlotNavigationImpl<Configuration>(
    private val coroutineScope: CoroutineScope,
) : MutableSlotNavigation<Configuration> {
    private val callbacksLock = ReentrantLock()
    private val callbacks: KoneMutableList<suspend (SlotNavigationEvent<Configuration>) -> Unit> = KoneMutableList.of()
    
    override fun subscribe(observer: suspend (SlotNavigationEvent<Configuration>) -> Unit) {
        callbacksLock.withLock {
            callbacks.add(observer)
        }
    }
    
    override suspend fun navigate(slotTransformation: SlotNavigationEvent<Configuration>) {
        callbacksLock.withLock {
            callbacks.toList()
        }.map { coroutineScope.launch { it(slotTransformation) } }.joinAll()
    }
}

public class InnerSlotNavigationState<Configuration> internal constructor(
    public val current: Configuration,
    configurationEquality: Equality<Configuration> = defaultEquality(),
    configurationHashing: Hashing<Configuration>? = null,
    configurationOrder: Order<Configuration>? = null,
) : NavigationState<Configuration> {
    override val configurations: KoneSet<Configuration> =
        KoneSet.of(
            current,
            elementEquality = configurationEquality,
            elementHashing = configurationHashing,
            elementOrder = configurationOrder,
        )
}

public typealias ChildrenSlot<Configuration, Component> = ChildWithConfiguration<Configuration, Component>

public suspend fun <
    Configuration,
    Child,
    Component,
> childrenSlot(
    configurationEquality: Equality<Configuration> = defaultEquality(),
    configurationHashing: Hashing<Configuration>? = null,
    configurationOrder: Order<Configuration>? = null,
    source: SlotNavigation<Configuration>,
    initialConfiguration: Configuration,
    createChild: suspend (configuration: Configuration, nextState: InnerSlotNavigationState<Configuration>) -> Child,
    destroyChild: suspend (configuration: Configuration, data: Child, nextState: InnerSlotNavigationState<Configuration>) -> Unit,
    updateChild: suspend (configuration: Configuration, data: Child, nextState: InnerSlotNavigationState<Configuration>) -> Unit,
    componentAccessor: suspend (Child) -> Component,
): KoneAsynchronousState<ChildrenSlot<Configuration, Component>> =
    children(
        configurationEquality = configurationEquality,
        configurationHashing = configurationHashing,
        configurationOrder = configurationOrder,
        source = source,
        initialState = InnerSlotNavigationState(
            current = initialConfiguration,
            configurationEquality = configurationEquality,
            configurationHashing = configurationHashing,
            configurationOrder = configurationOrder,
        ),
        navigationTransition = { previousState, event ->
            InnerSlotNavigationState(
                current = event(previousState.current),
                configurationEquality = configurationEquality,
                configurationHashing = configurationHashing,
                configurationOrder = configurationOrder,
            )
        },
        createChild = createChild,
        destroyChild = destroyChild,
        updateChild = updateChild,
        publicNavigationStateMapper = { innerState, componentByConfiguration ->
            ChildrenSlot(
                configuration = innerState.current,
                component = componentAccessor(componentByConfiguration[innerState.current]),
            )
        },
    )