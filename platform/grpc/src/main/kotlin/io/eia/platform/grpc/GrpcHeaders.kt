package io.eia.platform.grpc

import io.grpc.Metadata
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.context.propagation.TextMapSetter

/** gRPC の metadata の名前(INTEGRATION_STANDARDS §2。ADR-0042 §6)。 */
internal object GrpcHeaders {
    val CORRELATION_ID: Metadata.Key<String> = ascii("x-correlation-id")
    val AUTHORIZATION: Metadata.Key<String> = ascii("authorization")

    fun ascii(name: String): Metadata.Key<String> = Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER)

    val SETTER: TextMapSetter<Metadata> =
        TextMapSetter { carrier, key, value ->
            carrier?.let {
                val name = ascii(key)
                it.removeAll(name)
                it.put(name, value)
            }
        }

    val GETTER: TextMapGetter<Metadata> =
        object : TextMapGetter<Metadata> {
            override fun keys(carrier: Metadata): Iterable<String> = carrier.keys()

            override fun get(
                carrier: Metadata?,
                key: String,
            ): String? = carrier?.get(ascii(key))
        }
}
