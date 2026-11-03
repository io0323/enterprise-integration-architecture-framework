package io.eia.platform.reliability

// 違反: graphql-java は BFF の adapters と contract-check だけ(ADR-0043 §8)
import graphql.GraphQL

class UsesGraphql(
    val graphql: GraphQL,
)
