package dev.lounres.komponentual.navigation

import dev.lounres.kone.automata.AsynchronousAutomaton
import dev.lounres.kone.automata.CheckResult
import dev.lounres.kone.automata.move
import dev.lounres.kone.collections.iterables.next
import dev.lounres.kone.collections.map.KoneMap
import dev.lounres.kone.collections.map.KoneMutableMap
import dev.lounres.kone.collections.map.contains
import dev.lounres.kone.collections.map.of
import dev.lounres.kone.collections.set.KoneSet
import dev.lounres.kone.relations.Equality
import dev.lounres.kone.relations.Hashing
import dev.lounres.kone.relations.Order
import dev.lounres.kone.relations.defaultEquality
import dev.lounres.kone.state.KoneAsynchronousState
import dev.lounres.kone.state.KoneMutableAsynchronousState


public fun interface NavigationSource<out Event> {
    public fun subscribe(observer: suspend (Event) -> Unit)
}

public interface NavigationState<Configuration> {
    public val configurations: KoneSet<Configuration>
}

public suspend fun <
    Configuration,
    InnerNavigationState : NavigationState<Configuration>,
    PublicNavigationState,
    NavigationEvent,
    Child,
> children(
    configurationEquality: Equality<Configuration> = defaultEquality(),
    configurationHashing: Hashing<Configuration>? = null,
    configurationOrder: Order<Configuration>? = null,
    source: NavigationSource<NavigationEvent>,
    initialState: InnerNavigationState,
    navigationTransition: suspend (previousState: InnerNavigationState, event: NavigationEvent) -> InnerNavigationState,
    createChild: suspend (configuration: Configuration, nextState: InnerNavigationState) -> Child,
    destroyChild: suspend (Child) -> Unit,
    updateChild: suspend (configuration: Configuration, data: Child, nextState: InnerNavigationState) -> Unit,
    publicNavigationStateMapper: suspend (InnerNavigationState, KoneMap<Configuration, Child>) -> PublicNavigationState,
): KoneAsynchronousState<PublicNavigationState> {
    val components = KoneMutableMap.of<Configuration, Child>(
        keyEquality = configurationEquality,
        keyHashing = configurationHashing,
        keyOrder = configurationOrder,
    )
    for (configuration in initialState.configurations)
        components[configuration] = createChild(configuration, initialState)
    
    val result = KoneMutableAsynchronousState(publicNavigationStateMapper(initialState, components))
    
    val automaton = AsynchronousAutomaton<InnerNavigationState, NavigationEvent, Nothing>(
        initialState = initialState,
        checkTransition = { previousState, transition -> CheckResult.Success(navigationTransition(previousState, transition)) },
        onTransition = { _, _, nextState ->
            for (node in components.nodesView)
                if (node.key in nextState.configurations)
                    updateChild(node.key, node.value, nextState)
                else {
                    destroyChild(node.value)
                    node.remove()
                }
            
            for (configuration in nextState.configurations) if (configuration !in components)
                components[configuration] = createChild(configuration, nextState)
            
            result.set(publicNavigationStateMapper(nextState, components))
        }
    )
    
    source.subscribe { automaton.move(it) }
    
    return result
}

public class ChildWithConfiguration<out Configuration, out Component>(
    public val configuration: Configuration,
    public val component: Component,
)