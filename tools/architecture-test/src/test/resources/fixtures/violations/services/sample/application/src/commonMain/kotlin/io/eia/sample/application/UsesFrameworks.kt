package io.eia.sample.application

// 違反: application の commonMain でフレームワークを import、platform に依存
import io.eia.platform.observability.Tracer
import io.ktor.server.application.Application
import org.apache.kafka.clients.producer.KafkaProducer
import org.jetbrains.exposed.sql.Table
import org.koin.core.module.Module

class UsesFrameworks
