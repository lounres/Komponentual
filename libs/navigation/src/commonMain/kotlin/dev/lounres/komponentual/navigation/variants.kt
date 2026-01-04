package dev.lounres.komponentual.navigation

import dev.lounres.kone.collections.set.KoneSet
import dev.lounres.kone.contexts.invoke
import dev.lounres.kone.hub.KoneAsynchronousHub
import dev.lounres.kone.relations.Equality
import dev.lounres.kone.relations.Hashing
import dev.lounres.kone.relations.Order
import dev.lounres.kone.relations.defaultFor
import dev.lounres.kone.relations.eq
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder


public typealias VariantsNavigationEvent<Configuration> = (allVariants: KoneSet<Configuration>, Configuration) -> Configuration

public typealias VariantsNavigationSource<Configuration> = NavigationSource<VariantsNavigationEvent<Configuration>>

public typealias VariantsNavigationTarget<Configuration> = NavigationTarget<VariantsNavigationEvent<Configuration>>

public suspend fun <Configuration> VariantsNavigationTarget<Configuration>.set(configuration: Configuration) {
    navigate { _, _ -> configuration }
}

public typealias VariantsNavigationHub<Configuration> = NavigationHub<VariantsNavigationEvent<Configuration>>

public fun <Configuration> VariantsNavigationHub(): VariantsNavigationHub<Configuration> = NavigationHub()

public data class VariantsNavigationState<Configuration>(
    public val configurations: KoneSet<Configuration>,
    public val currentVariant: Configuration,
) {
    public class Serializer<Configuration>(
        private val configurationSerializer: KSerializer<Configuration>,
        private val allConfigurations: KoneSet<Configuration>,
    ) : KSerializer<VariantsNavigationState<Configuration>> {
        override val descriptor: SerialDescriptor = configurationSerializer.descriptor
        
        override fun serialize(encoder: Encoder, value: VariantsNavigationState<Configuration>) {
            encoder.encodeSerializableValue(configurationSerializer, value.currentVariant)
        }
        
        override fun deserialize(decoder: Decoder): VariantsNavigationState<Configuration> =
            VariantsNavigationState(
                configurations = allConfigurations,
                currentVariant = decoder.decodeSerializableValue(configurationSerializer)
            )
    }
}

public suspend fun <
    Configuration,
    Child,
> childrenVariants(
    configurationEquality: Equality<Configuration> = Equality.defaultFor(),
    configurationHashing: Hashing<Configuration>? = null,
    configurationOrder: Order<Configuration>? = null,
    childEquality: Equality<Child> = Equality.defaultFor(),
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
        navigationStateEquality = Equality { left, right ->
            configurationEquality { left.currentVariant eq right.currentVariant }
        },
        childEquality = childEquality,
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