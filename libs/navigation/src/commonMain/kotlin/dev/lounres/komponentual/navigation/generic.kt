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
import dev.lounres.kone.hub.KoneAsynchronousHub
import dev.lounres.kone.hub.KoneMutableAsynchronousHub
import dev.lounres.kone.hub.set
import dev.lounres.kone.relations.Equality
import dev.lounres.kone.relations.Hashing
import dev.lounres.kone.relations.Order
import dev.lounres.kone.relations.defaultFor
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


public fun interface NavigationSource<out Event> {
    public fun subscribe(observer: suspend (Event) -> Unit)
}

public data class NavigationResult<out NavigationStateType, Configuration, out Child>(
    public val navigationState: NavigationStateType,
    public val children: KoneMap<Configuration, Child>,
)

public suspend fun <
    Configuration,
    NavigationState,
    NavigationEvent,
    Child,
> children(
    configurationEquality: Equality<Configuration> = Equality.defaultFor(),
    configurationHashing: Hashing<Configuration>? = null,
    configurationOrder: Order<Configuration>? = null,
    source: NavigationSource<NavigationEvent>,
    initialState: NavigationState,
    stateConfigurationsMapping: (NavigationState) -> KoneSet<Configuration>,
    navigationTransition: suspend (previousState: NavigationState, event: NavigationEvent) -> NavigationState,
    createChild: suspend (configuration: Configuration, nextState: NavigationState) -> Child,
    destroyChild: suspend (configuration: Configuration, data: Child, nextState: NavigationState) -> Unit,
    updateChild: suspend (configuration: Configuration, data: Child, nextState: NavigationState) -> Unit,
): KoneAsynchronousHub<NavigationResult<NavigationState, Configuration, Child>> {
    val componentsMutex = Mutex()
    val components = KoneMutableMap.of<Configuration, Child>(
        keyEquality = configurationEquality,
        keyHashing = configurationHashing,
        keyOrder = configurationOrder,
    )
    for (configuration in stateConfigurationsMapping(initialState))
        components[configuration] = createChild(configuration, initialState)
    
    val result = KoneMutableAsynchronousHub(NavigationResult(initialState, components), Equality.defaultFor() /* FIXME: Implement actual equality */)
    
    val automaton = AsynchronousAutomaton<NavigationState, NavigationEvent, Nothing>(
        initialState = initialState,
        checkTransition = { previousState, transition -> CheckResult.Success(navigationTransition(previousState, transition)) },
        onTransition = { _, _, nextState ->
            supervisorScope {
                componentsMutex.withLock {
                    val newComponents = stateConfigurationsMapping(nextState)
                    
                    for (node in components.nodesView)
                        if (node.key in newComponents) launch {
                            updateChild(node.key, node.value, nextState)
                        } else launch {
                            destroyChild(node.key, node.value, nextState)
                            componentsMutex.withLock {
                                node.remove()
                            }
                        }
                    
                    for (configuration in newComponents) if (configuration !in components) launch {
                        val newChild = createChild(configuration, nextState)
                        componentsMutex.withLock {
                            components[configuration] = newChild
                        }
                    }
                }
            }
            result.set(NavigationResult(nextState, components))
        }
    )
    
    source.subscribe { automaton.move(it) }
    
    return result
}