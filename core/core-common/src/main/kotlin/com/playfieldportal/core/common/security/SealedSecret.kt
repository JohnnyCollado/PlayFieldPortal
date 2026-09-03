package com.playfieldportal.core.common.security

/**
 * The outcome of sealing a secret for storage.
 *
 * Sealing can fail — a device whose Keystore is unavailable, or a key that was invalidated — and
 * the app's rule is that the user's input is never lost when it does. That rule used to be
 * implemented by silently returning the plaintext, which meant a credential could be stored
 * unprotected with nothing anywhere saying so. This type keeps the same behaviour (the value is
 * always stored) while making the difference impossible to drop on the floor: a caller that wants
 * to warn the user can, and a caller that does not still has to acknowledge the type.
 */
sealed interface SealedSecret {

    /** The value to persist. Present on both outcomes — storing it always works. */
    val stored: String

    /** True only when [stored] is protected by the device Keystore. */
    val isProtected: Boolean

    /** Sealed with the device-bound Keystore key; [stored] is Base64(iv ‖ ciphertext). */
    data class Sealed(override val stored: String) : SealedSecret {
        override val isProtected: Boolean get() = true
    }

    /**
     * The Keystore refused to seal, so [stored] is the plaintext. Callers that own a user-facing
     * surface should say so rather than implying the secret is encrypted at rest.
     */
    data class Unprotected(override val stored: String, val cause: Throwable) : SealedSecret {
        override val isProtected: Boolean get() = false
    }
}

/**
 * What a credential store can tell its caller after a write. Deliberately not a Boolean: these
 * values are shown to the user, and `false` reads as "the save failed" when what actually happened
 * is "the save succeeded but the value is not encrypted".
 */
enum class SecretProtection {
    /** Encrypted at rest with the device Keystore key. */
    PROTECTED,

    /** Stored, but in plaintext — the Keystore was unavailable. Worth telling the user. */
    UNPROTECTED,
    ;

    companion object {
        fun of(vararg secrets: SealedSecret): SecretProtection =
            if (secrets.all { it.isProtected }) PROTECTED else UNPROTECTED
    }
}
