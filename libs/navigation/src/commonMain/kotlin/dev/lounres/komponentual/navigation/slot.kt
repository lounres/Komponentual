package dev.lounres.komponentual.navigation

import dev.lounres.kone.collections.set.KoneSet
import dev.lounres.kone.collections.set.of
import dev.lounres.kone.hub.KoneAsynchronousHub
import dev.lounres.kone.relations.Equality
import dev.lounres.kone.relations.Hashing
import dev.lounres.kone.relations.Order
import dev.lounres.kone.relations.defaultFor


public typealias SlotNavigationEvent<Configuration> = (Configuration) -> Configuration

public typealias SlotNavigationSource<Configuration> = NavigationSource<SlotNavigationEvent<Configuration>>

public typealias SlotNavigationTarget<Configuration> = NavigationTarget<SlotNavigationEvent<Configuration>>

public suspend fun <Configuration> SlotNavigationTarget<Configuration>.set(configuration: Configuration) {
    navigate { configuration }
}

public typealias SlotNavigationHub<Configuration> = NavigationHub<SlotNavigationEvent<Configuration>>

public fun <Configuration> SlotNavigationHub(): SlotNavigationHub<Configuration> = NavigationHub()

public suspend fun <
    Configuration,
    Child,
> childrenSlot(
    configurationEquality: Equality<Configuration> = Equality.defaultFor(),
    configurationHashing: Hashing<Configuration>? = null,
    configurationOrder: Order<Configuration>? = null,
    childEquality: Equality<Child> = Equality.defaultFor(),
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
        navigationStateEquality = configurationEquality,
        childEquality = childEquality,
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