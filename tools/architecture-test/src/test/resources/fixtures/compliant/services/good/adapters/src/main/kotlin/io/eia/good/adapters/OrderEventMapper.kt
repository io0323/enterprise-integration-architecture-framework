package io.eia.good.adapters

// 適合: adapters はイベントの型(avro4k)と Kafka の型を使ってよい
import com.github.avrokotlin.avro4k.Avro
import org.apache.avro.Schema
import org.apache.kafka.common.header.Header

class OrderEventMapper(
    val avro: Avro,
    val schema: Schema,
    val headers: List<Header>,
)
