package dev.lounres.komponentual.navigation

import dev.lounres.kone.collections.list.KoneList
import dev.lounres.kone.collections.list.build
import dev.lounres.kone.collections.list.lastIndex
import dev.lounres.kone.collections.list.relations.equality
import dev.lounres.kone.collections.set.KoneMutableSet
import dev.lounres.kone.collections.set.of
import dev.lounres.kone.collections.utils.copyTo
import dev.lounres.kone.collections.utils.dropLast
import dev.lounres.kone.collections.utils.last
import dev.lounres.kone.collections.utils.plusAssign
import dev.lounres.kone.hub.KoneAsynchronousHub
import dev.lounres.kone.relations.Equality
import dev.lounres.kone.relations.Hashing
import dev.lounres.kone.relations.Order
import dev.lounres.kone.relations.defaultFor


public typealias StackNavigationEvent<Configuration> = (stack: KoneList<Configuration>) -> KoneList<Configuration>

public typealias StackNavigationSource<Configuration> = NavigationSource<StackNavigationEvent<Configuration>>

public typealias StackNavigationTarget<Configuration> = NavigationTarget<StackNavigationEvent<Configuration>>

public suspend fun <Configuration> StackNavigationTarget<Configuration>.push(configuration: Configuration) {
    navigate { stack ->
        KoneList.build(stack.size + 1u) {
            this += stack
            +configuration
        }
    }
}

public suspend fun <Configuration> StackNavigationTarget<Configuration>.pop() {
    navigate { stack -> stack.dropLast(1u) }
}

public suspend fun <Configuration> StackNavigationTarget<Configuration>.replaceCurrent(configuration: Configuration) {
    navigate { stack ->
        KoneList.build(stack.size) {
            this += stack
            removeAt(lastIndex)
            +configuration
        }
    }
}

public suspend fun <Configuration> StackNavigationTarget<Configuration>.updateCurrent(update: (Configuration) -> Configuration) {
    navigate { stack ->
        KoneList.build(stack.size) {
            this += stack
            val previouslyLastConfiguration = last()
            removeAt(lastIndex)
            +update(previouslyLastConfiguration)
        }
    }
}

public typealias StackNavigationHub<Configuration> = NavigationHub<StackNavigationEvent<Configuration>>

public fun <Configuration> StackNavigationHub(): StackNavigationHub<Configuration> = NavigationHub()

public typealias StackNavigationState<Configuration> = KoneList<Configuration>

public suspend fun <
    Configuration,
    Child,
> childrenStack(
    configurationEquality: Equality<Configuration> = Equality.defaultFor(),
    configurationHashing: Hashing<Configuration>? = null,
    configurationOrder: Order<Configuration>? = null,
    childEquality: Equality<Child> = Equality.defaultFor(),
    source: StackNavigationSource<Configuration>,
    initialStack: KoneList<Configuration>,
    createChild: suspend (configuration: Configuration, nextState: StackNavigationState<Configuration>) -> Child,
    destroyChild: suspend (configuration: Configuration, data: Child, nextState: StackNavigationState<Configuration>) -> Unit,
    updateChild: suspend (configuration: Configuration, data: Child, nextState: StackNavigationState<Configuration>) -> Unit,
): KoneAsynchronousHub<NavigationResult<KoneList<Configuration>, Configuration, Child>> =
    children(
        configurationEquality = configurationEquality,
        configurationHashing = configurationHashing,
        configurationOrder = configurationOrder,
        navigationStateEquality = KoneList.equality(configurationEquality),
        childEquality = childEquality,
        source = source,
        initialState = initialStack.also { require(it.size != 0u) { "Cannot initialize a children stack without configurations" } },
        stateConfigurationsMapping = { currentNavigationState ->
            currentNavigationState.copyTo(
                KoneMutableSet.of(
                    elementEquality = configurationEquality,
                    elementHashing = configurationHashing,
                    elementOrder = configurationOrder,
                )
            )
        },
        navigationTransition = { previousState, event ->
            event(previousState).also { require(it.size != 0u) { "Cannot initialize a children stack without configurations" } }
        },
        createChild = createChild,
        destroyChild = destroyChild,
        updateChild = updateChild,
    )