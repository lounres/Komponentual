package dev.lounres.komponentual.navigation

import dev.lounres.kone.collections.list.KoneMutableList
import dev.lounres.kone.collections.list.of
import dev.lounres.kone.collections.list.toKoneList
import dev.lounres.kone.collections.set.KoneSet
import dev.lounres.kone.collections.utils.forEach
import dev.lounres.kone.hub.KoneAsynchronousHub
import dev.lounres.kone.relations.Equality
import dev.lounres.kone.relations.Hashing
import dev.lounres.kone.relations.Order
import dev.lounres.kone.relations.defaultEquality
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope


public typealias VariantsNavigationEvent<Configuration> = (allVariants: KoneSet<Configuration>, Configuration) -> Configuration

public typealias VariantsNavigationSource<Configuration> = NavigationSource<VariantsNavigationEvent<Configuration>>

public fun interface VariantsNavigationTarget<Configuration> {
    public suspend fun navigate(variantsTransformation: VariantsNavigationEvent<Configuration>)
}

public suspend fun <Configuration> VariantsNavigationTarget<in Configuration>.set(configuration: Configuration) {
    navigate { _, _ -> configuration }
}

public interface VariantsNavigationHub<Configuration> : VariantsNavigationSource<Configuration>, VariantsNavigationTarget<Configuration>

public fun <Configuration> VariantsNavigationHub(): VariantsNavigationHub<Configuration> = VariantsNavigationHubImpl()

internal class VariantsNavigationHubImpl<Configuration>: VariantsNavigationHub<Configuration> {
    private val callbacksLock = ReentrantLock()
    private val callbacks: KoneMutableList<suspend (VariantsNavigationEvent<Configuration>) -> Unit> = KoneMutableList.of()
    
    override fun subscribe(observer: suspend (VariantsNavigationEvent<Configuration>) -> Unit) {
        callbacksLock.withLock {
            callbacks.add(observer)
        }
    }
    
    override suspend fun navigate(variantsTransformation: VariantsNavigationEvent<Configuration>) {
        val callbacksToLaunch = callbacksLock.withLock {
            callbacks.toKoneList()
        }
        supervisorScope {
            callbacksToLaunch.forEach { callback ->
                launch {
                    callback(variantsTransformation)
                }
            }
        }
    }
}

public data class VariantsNavigationState<Configuration> internal constructor(
    public val configurations: KoneSet<Configuration>,
    public val currentVariant: Configuration,
)

public suspend fun <
    Configuration,
    Child,
> childrenVariants(
    configurationEquality: Equality<Configuration> = defaultEquality(),
    configurationHashing: Hashing<Configuration>? = null,
    configurationOrder: Order<Configuration>? = null,
    source: VariantsNavigationSource<Configuration>,
    allVariants: KoneSet<Configuration>,
    initialVariant: Configuration,
    createChild: suspend (configuration: Configuration, nextState: VariantsNavigationState<Configuration>) -> Child,
    destroyChild: suspend (configuration: Configuration, data: Child, nextState: VariantsNavigationState<Configuration>) -> Unit,
    updateChild: suspend (configuration: Configuration, data: Child, nextState: VariantsNavigationState<Configuration>) -> Unit,
): KoneAsynchronousHub<NavigationResult<VariantsNavigationState<Configuration>, Configuration, Child>> =
    children(
        configurationEquality = configurationEquality,
        configurationHashing = configurationHashing,
        configurationOrder = configurationOrder,
        source = source,
        initialState = VariantsNavigationState(
            configurations = allVariants,
            currentVariant = initialVariant,
        ),
        stateConfigurationsMapping = { currentNavigationState -> currentNavigationState.configurations },
        navigationTransition = { previousState, event ->
            VariantsNavigationState(
                configurations = previousState.configurations,
                currentVariant = event(previousState.configurations, previousState.currentVariant)
            )
        },
        createChild = createChild,
        destroyChild = destroyChild,
        updateChild = updateChild,
    )