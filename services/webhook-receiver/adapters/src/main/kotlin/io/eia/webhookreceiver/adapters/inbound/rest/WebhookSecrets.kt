package io.eia.webhookreceiver.adapters.inbound.rest

import io.eia.platform.security.secret.Secret
import io.eia.platform.security.secret.SecretName
import io.eia.platform.security.secret.SecretProvider
import io.eia.shared.kernel.Result

/**
 * 受け口が受け付ける署名の鍵(ADR-0035 §2)。今の鍵([current])と、差し替えの間だけ差し替え前の鍵([previous]。`{name}_PREVIOUS`)。
 * 鍵は要求のたびに [SecretProvider] から読む(再起動なしで差し替える。値はキャッシュしない)。
 * 今の鍵を読めなければ空(受け口は 503 を返し、送り手に再送させる)。
 */
public class WebhookSecrets(
    private val provider: SecretProvider,
    private val current: SecretName,
) {
    private val previous: SecretName = SecretName(current.value + PREVIOUS_SUFFIX)

    public fun read(): List<Secret> {
        val now = (provider.get(current) as? Result.Ok)?.value ?: return emptyList()
        return listOfNotNull(now, (provider.get(previous) as? Result.Ok)?.value)
    }

    public companion object {
        public const val PREVIOUS_SUFFIX: String = "_PREVIOUS"
    }
}
