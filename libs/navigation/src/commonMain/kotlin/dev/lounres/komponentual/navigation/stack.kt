package dev.lounres.komponentual.navigation

import dev.lounres.kone.cast
import dev.lounres.kone.collections.list.*
import dev.lounres.kone.collections.set.KoneMutableSet
import dev.lounres.kone.collections.set.of
import dev.lounres.kone.collections.utils.dropLast
import dev.lounres.kone.collections.utils.forEach
import dev.lounres.kone.collections.utils.last
import dev.lounres.kone.collections.utils.mapTo
import dev.lounres.kone.collections.utils.plusAssign
import dev.lounres.kone.hub.KoneAsynchronousHub
import dev.lounres.kone.relations.Equality
import dev.lounres.kone.relations.Hashing
import dev.lounres.kone.relations.Order
import dev.lounres.kone.relations.defaultFor
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope


public typealias StackNavigationEvent<Configuration> = (stack: KoneList<Configuration>) -> KoneList<Configuration>

public typealias StackNavigationSource<Configuration> = NavigationSource<StackNavigationEvent<Configuration>>

public fun interface StackNavigationTarget<Configuration> {
    public suspend fun navigate(stackTransformation: StackNavigationEvent<Configuration>)
}

public suspend fun <Configuration> StackNavigationTarget<in Configuration>.push(configuration: Configuration) {
    navigate { stack ->
        KoneList.build(stack.size + 1u) {
            this += stack.cast<KoneList<Configuration>>()
            +configuration
        }
    }
}

public suspend fun StackNavigationTarget<*>.pop() {
    navigate { stack -> stack.dropLast(1u) }
}

public suspend fun <Configuration> StackNavigationTarget<in Configuration>.replaceCurrent(configuration: Configuration) {
    navigate { stack ->
        KoneList.build(stack.size) {
            this += stack.cast<KoneList<Configuration>>()
            removeAt(lastIndex)
            +configuration
        }
    }
}

public suspend fun <C> StackNavigationTarget<C>.updateCurrent(update: (C) -> C) {
    navigate { stack ->
        KoneList.build(stack.size) {
            this += stack
            val previouslyLastConfiguration = last()
            removeAt(lastIndex)
            +update(previouslyLastConfiguration)
        }
    }
}

public interface StackNavigationHub<Configuration> : StackNavigationSource<Configuration>, StackNavigationTarget<Configuration>

public fun <Configuration> StackNavigationHub(): StackNavigationHub<Configuration> =
    StackNavigationHubImpl()

internal class StackNavigationHubImpl<Configuration> : StackNavigationHub<Configuration> {
    private val callbacksLock = ReentrantLock()
    private val callbacks: KoneMutableList<suspend (StackNavigationEvent<Configuration>) -> Unit> = KoneMutableList.of()
    
    override fun subscribe(observer: suspend (StackNavigationEvent<Configuration>) -> Unit) {
        callbacksLock.withLock {
            callbacks.add(observer)
        }
    }
    
    override suspend fun navigate(stackTransformation: StackNavigationEvent<Configuration>) {
        val callbacksToLaunch = callbacksLock.withLock {
            callbacks.toKoneList()
        }
        supervisorScope {
            callbacksToLaunch.forEach { callback ->
                launch {
                    callback(stackTransformation)
                }
            }
        }
    }
}

public typealias StackNavigationState<Configuration> = KoneList<Configuration>

public suspend fun <
    Configuration,
    Child,
> childrenStack(
    configurationEquality: Equality<Configuration> = Equality.defaultFor(),
    configurationHashing: Hashing<Configuration>? = null,
    configurationOrder: Order<Configuration>? = null,
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
        source = source,
        initialState = initialStack.also { require(it.size != 0u) { "Cannot initialize a children stack without configurations" } },
        stateConfigurationsMapping = { currentNavigationState ->
            currentNavigationState.mapTo(
                KoneMutableSet.of(
                    elementEquality = configurationEquality,
                    elementHashing = configurationHashing,
                    elementOrder = configurationOrder,
                )
            ) { it }
        },
        navigationTransition = { previousState, event ->
            event(previousState).also { require(it.size != 0u) { "Cannot initialize a children stack without configurations" } }
        },
        createChild = createChild,
        destroyChild = destroyChild,
        updateChild = updateChild,
    )