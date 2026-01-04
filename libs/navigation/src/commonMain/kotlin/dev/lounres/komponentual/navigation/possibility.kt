package dev.lounres.komponentual.navigation

import dev.lounres.kone.collections.set.KoneSet
import dev.lounres.kone.collections.set.empty
import dev.lounres.kone.collections.set.of
import dev.lounres.kone.contexts.invoke
import dev.lounres.kone.hub.KoneAsynchronousHub
import dev.lounres.kone.maybe.Maybe
import dev.lounres.kone.maybe.None
import dev.lounres.kone.maybe.Some
import dev.lounres.kone.relations.Equality
import dev.lounres.kone.relations.Hashing
import dev.lounres.kone.relations.Order
import dev.lounres.kone.relations.defaultFor
import dev.lounres.kone.relations.eq


public typealias PossibilityNavigationEvent<Configuration> = (Maybe<Configuration>) -> Maybe<Configuration>

public typealias PossibilityNavigationSource<Configuration> = NavigationSource<PossibilityNavigationEvent<Configuration>>

public typealias PossibilityNavigationTarget<Configuration> = NavigationTarget<PossibilityNavigationEvent<Configuration>>

public suspend fun <Configuration> PossibilityNavigationTarget<Configuration>.set(configuration: Maybe<Configuration>) {
    navigate { configuration }
}

public suspend fun <Configuration> PossibilityNavigationTarget<Configuration>.set(configuration: Configuration) {
    navigate { Some(configuration) }
}

public suspend fun <Configuration> PossibilityNavigationTarget<Configuration>.clear() {
    navigate { None }
}

public typealias PossibilityNavigationHub<Configuration> = NavigationHub<PossibilityNavigationEvent<Configuration>>

public fun <Configuration> PossibilityNavigationHub(): PossibilityNavigationHub<Configuration> = NavigationHub()

public typealias PossibilityNavigationState<Configuration> = Maybe<Configuration>

public suspend fun <
    Configuration,
    Child,
> childrenPossibility(
    configurationEquality: Equality<Configuration> = Equality.defaultFor(),
    configurationHashing: Hashing<Configuration>? = null,
    configurationOrder: Order<Configuration>? = null,
    childEquality: Equality<Child> = Equality.defaultFor(),
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
        navigationStateEquality = Equality { left, right ->
            (left === None && right === None) || (left is Some && right is Some && configurationEquality { left.value eq right.value })
        },
        childEquality = childEquality,
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