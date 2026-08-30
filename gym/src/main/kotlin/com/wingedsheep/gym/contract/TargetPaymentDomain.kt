package com.wingedsheep.gym.contract

import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.Serializable

const val TARGET_PAYMENT_DOMAIN_V1_VERSION: Int = 1

/** Complete target-to-payment relation for the bounded target-dependent payment slice. */
@Serializable
data class TargetPaymentDomainV1(
    val version: Int = TARGET_PAYMENT_DOMAIN_V1_VERSION,
    val targetBindings: List<TargetPaymentBindingV1>,
) {
    init {
        require(version == TARGET_PAYMENT_DOMAIN_V1_VERSION) {
            "Unsupported target payment domain version: $version"
        }
        require(targetBindings.isNotEmpty()) {
            "TargetPaymentDomainV1 must contain at least one target binding"
        }
        require(targetBindings.map { it.target }.distinct().size == targetBindings.size) {
            "TargetPaymentDomainV1 cannot contain duplicate target bindings"
        }
    }
}

/** One exact target binding and its complete, non-null PaymentDomainV5 capability. */
@Serializable
data class TargetPaymentBindingV1(
    val target: EntityId,
    val affordable: Boolean,
    val paymentDomain: PaymentDomainV5,
)
