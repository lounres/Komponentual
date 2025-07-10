package dev.lounres.komponentual.navigation

import dev.lounres.kone.collections.interop.toList
import dev.lounres.kone.collections.list.*
import dev.lounres.kone.collections.map.get
import dev.lounres.kone.collections.set.KoneSet
import dev.lounres.kone.collections.set.toKoneSet
import dev.lounres.kone.collections.utils.dropLast
import dev.lounres.kone.collections.utils.last
import dev.lounres.kone.collections.utils.map
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


public typealias StackNavigationEvent<Configuration> = (stack: KoneList<Configuration>) -> KoneList<Configuration>

public typealias StackNavigation<Configuration> = NavigationSource<StackNavigationEvent<Configuration>>

public interface MutableStackNavigation<Configuration> : StackNavigation<Configuration> {
    public suspend fun navigate(stackTransformation: StackNavigationEvent<Configuration>)
}

public fun <Configuration> MutableStackNavigation(coroutineScope: CoroutineScope): MutableStackNavigation<Configuration> =
    MutableStackNavigationImpl(coroutineScope = coroutineScope)

public suspend fun <Configuration> MutableStackNavigation<Configuration>.push(configuration: Configuration) {
    navigate { stack ->
        KoneList.build(stack.size + 1u) {
            +stack
            +configuration
        }
    }
}

public suspend fun <Configuration> MutableStackNavigation<Configuration>.pop() {
    navigate { stack -> stack.dropLast(1u) }
}

public suspend fun <Configuration> MutableStackNavigation<Configuration>.replaceCurrent(configuration: Configuration) {
    navigate { stack ->
        KoneList.build(stack.size + 1u) {
            +stack
            removeAt(stack.lastIndex)
            +configuration
        }
    }
}

public suspend fun <C> MutableStackNavigation<C>.updateCurrent(update: (C) -> C) {
    navigate { stack ->
        KoneList.build(stack.size + 1u) {
            +stack
            removeAt(stack.lastIndex)
            +update(stack.last())
        }
    }
}

internal class MutableStackNavigationImpl<Configuration>(
    private val coroutineScope: CoroutineScope,
) : MutableStackNavigation<Configuration> {
    private val callbacksLock = ReentrantLock()
    private val callbacks: KoneMutableList<suspend (StackNavigationEvent<Configuration>) -> Unit> = KoneMutableList.of()
    
    override fun subscribe(observer: suspend (StackNavigationEvent<Configuration>) -> Unit) {
        callbacksLock.withLock {
            callbacks.add(observer)
        }
    }
    
    override suspend fun navigate(stackTransformation: StackNavigationEvent<Configuration>) {
        callbacksLock.withLock {
            callbacks.toList()
        }.map { coroutineScope.launch { it(stackTransformation) } }.joinAll()
    }
}

public class InnerStackNavigationState<Configuration> internal constructor(
    public val stack: KoneList<Configuration>,
    configurationEquality: Equality<Configuration> = defaultEquality(),
    configurationHashing: Hashing<Configuration>? = null,
    configurationOrder: Order<Configuration>? = null,
) : NavigationState<Configuration> {
    override val configurations: KoneSet<Configuration> =
        stack.toKoneSet(
            elementEquality = configurationEquality,
            elementHashing = configurationHashing,
            elementOrder = configurationOrder,
        )
}

public data class ChildrenStack<out Configuration, out Component>(
    public val active: ChildWithConfiguration<Configuration, Component>,
    public val backStack: KoneList<ChildWithConfiguration<Configuration, Component>> = KoneList.empty(),
)

public fun <Configuration, Component> ChildrenStack(configuration: Configuration, component: Component): ChildrenStack<Configuration, Component> =
    ChildrenStack(
        active = ChildWithConfiguration(configuration, component),
    )

public suspend fun <
    Configuration,
    Child,
    Component,
> childrenStack(
    configurationEquality: Equality<Configuration> = defaultEquality(),
    configurationHashing: Hashing<Configuration>? = null,
    configurationOrder: Order<Configuration>? = null,
    source: StackNavigation<Configuration>,
    initialStack: KoneList<Configuration>,
    createChild: suspend (configuration: Configuration, nextState: InnerStackNavigationState<Configuration>) -> Child,
    destroyChild: suspend (configuration: Configuration, data: Child, nextState: InnerStackNavigationState<Configuration>) -> Unit,
    updateChild: suspend (configuration: Configuration, data: Child, nextState: InnerStackNavigationState<Configuration>) -> Unit,
    componentAccessor: suspend (Child) -> Component,
): KoneAsynchronousState<ChildrenStack<Configuration, Component>> =
    children(
        configurationEquality = configurationEquality,
        configurationHashing = configurationHashing,
        configurationOrder = configurationOrder,
        source = source,
        initialState = InnerStackNavigationState(
            stack = initialStack.also { require(it.size != 0u) { "Cannot initialize a children stack without configurations" } },
            configurationEquality = configurationEquality,
            configurationHashing = configurationHashing,
            configurationOrder = configurationOrder,
        ),
        navigationTransition = { previousState, event ->
            InnerStackNavigationState(
                stack = event(previousState.stack).also { require(it.size != 0u) { "Cannot initialize a children stack without configurations" } },
                configurationEquality = configurationEquality,
                configurationHashing = configurationHashing,
                configurationOrder = configurationOrder,
            )
        },
        createChild = createChild,
        destroyChild = destroyChild,
        updateChild = updateChild,
        publicNavigationStateMapper = { innerState, componentByConfiguration ->
            val stack = innerState.stack
                .also {
                    check(it.size != 0u) { "Navigation stack is empty for some reason" }
                }
                .map {
                    ChildWithConfiguration(
                        configuration = it,
                        component = componentAccessor(componentByConfiguration[it]),
                    )
                }
            ChildrenStack(
                active = stack.last(),
                backStack = stack.dropLast(1u),
            )
        },
    )