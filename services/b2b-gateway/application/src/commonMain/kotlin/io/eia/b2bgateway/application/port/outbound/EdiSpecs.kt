package io.eia.b2bgateway.application.port.outbound

import io.eia.b2bgateway.domain.spec.EdiSpec

/** EDI のサブセットの仕様(contracts/files/edi。ADR-0041 §7)。adapters が契約の YAML から読み込む。 */
public interface EdiSpecs {
    /** 受け取る受注の文書(edifact-orders.v1) */
    public val orders: EdiSpec

    /** 構文とスキーマの段階の応答(edifact-contrl.v1) */
    public val contrl: EdiSpec

    /** 業務の段階の応答(edifact-aperak.v1) */
    public val aperak: EdiSpec
}
