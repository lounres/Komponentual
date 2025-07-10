package dev.lounres.komponentual.navigation

import dev.lounres.kone.collections.interop.toList
import dev.lounres.kone.collections.list.KoneMutableList
import dev.lounres.kone.collections.list.of
import dev.lounres.kone.collections.map.KoneMap
import dev.lounres.kone.collections.map.associateWith
import dev.lounres.kone.collections.map.get
import dev.lounres.kone.collections.set.KoneSet
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


public typealias VariantsNavigationEvent<Configuration> = (allVariants: KoneSet<Configuration>, Configuration) -> Configuration

public typealias VariantsNavigation<Configuration> = NavigationSource<VariantsNavigationEvent<Configuration>>

public interface MutableVariantsNavigation<Configuration> : VariantsNavigation<Configuration> {
    public suspend fun navigate(variantsTransformation: VariantsNavigationEvent<Configuration>)
}

public fun <Configuration> MutableVariantsNavigation(coroutineScope: CoroutineScope): MutableVariantsNavigation<Configuration> =
    MutableVariantsNavigationImpl(coroutineScope = coroutineScope)

public suspend fun <Configuration> MutableVariantsNavigation<Configuration>.set(configuration: Configuration) {
    navigate { _, _ -> configuration }
}

internal class MutableVariantsNavigationImpl<Configuration>(
    private val coroutineScope: CoroutineScope,
) : MutableVariantsNavigation<Configuration> {
    private val callbacksLock = ReentrantLock()
    private val callbacks: KoneMutableList<suspend (VariantsNavigationEvent<Configuration>) -> Unit> = KoneMutableList.of()
    
    override fun subscribe(observer: suspend (VariantsNavigationEvent<Configuration>) -> Unit) {
        callbacksLock.withLock {
            callbacks.add(observer)
        }
    }
    
    override suspend fun navigate(variantsTransformation: VariantsNavigationEvent<Configuration>) {
        callbacksLock.withLock {
            callbacks.toList()
        }.map { coroutineScope.launch { it(variantsTransformation) } }.joinAll()
    }
}

public class InnerVariantsNavigationState<Configuration> internal constructor(
    override val configurations: KoneSet<Configuration>,
    public val currentVariant: Configuration,
) : NavigationState<Configuration>

public data class ChildrenVariants<Configuration, out Component>(
    public val active: ChildWithConfiguration<Configuration, Component>,
    public val allVariants: KoneMap<Configuration, Component>,
)

public suspend fun <
    Configuration,
    Child,
    Component,
> childrenVariants(
    configurationEquality: Equality<Configuration> = defaultEquality(),
    configurationHashing: Hashing<Configuration>? = null,
    configurationOrder: Order<Configuration>? = null,
    source: VariantsNavigation<Configuration>,
    allVariants: KoneSet<Configuration>,
    initialVariant: Configuration,
    createChild: suspend (configuration: Configuration, nextState: InnerVariantsNavigationState<Configuration>) -> Child,
    destroyChild: suspend (configuration: Configuration, Child) -> Unit,
    updateChild: suspend (configuration: Configuration, data: Child, nextState: InnerVariantsNavigationState<Configuration>) -> Unit,
    componentAccessor: suspend (Child) -> Component,
): KoneAsynchronousState<ChildrenVariants<Configuration, Component>> =
    children(
        configurationEquality = configurationEquality,
        configurationHashing = configurationHashing,
        configurationOrder = configurationOrder,
        source = source,
        initialState = InnerVariantsNavigationState(
            configurations = allVariants,
            currentVariant = initialVariant,
        ),
        navigationTransition = { previousState, event ->
            InnerVariantsNavigationState(
                configurations = previousState.configurations,
                currentVariant = event(previousState.configurations, previousState.currentVariant)
            )
        },
        createChild = createChild,
        destroyChild = destroyChild,
        updateChild = updateChild,
        publicNavigationStateMapper = { innerState, componentByConfiguration ->
            ChildrenVariants(
                active = ChildWithConfiguration(
                    configuration = innerState.currentVariant,
                    component = componentAccessor(componentByConfiguration[innerState.currentVariant]),
                ),
                allVariants = innerState.configurations.associateWith { componentAccessor(componentByConfiguration[it]) },
            )
        },
    )