package com.altaran.watchwallet.core.security

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * All "secret" samples below are public test vectors or random throwaway values that have never
 * held funds. Never put a real key in this file.
 */
class SecretGuardTest {
    private fun assertSecret(kind: SecretKind, input: String) =
        assertEquals(SecretCheck.Secret(kind), SecretGuard.check(input), "expected $kind for: $input")

    private fun assertClean(input: String) =
        assertEquals(SecretCheck.Clean, SecretGuard.check(input), "expected clean: $input")

    @Test
    fun mnemonicsAreRejected() {
        val bip39 = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        assertSecret(SecretKind.MNEMONIC, bip39)
        assertSecret(SecretKind.MNEMONIC, bip39.replace(" ", "\n"))
        assertSecret(SecretKind.MNEMONIC, bip39.uppercase())
        assertSecret(SecretKind.MNEMONIC, "  $bip39  ")
        // 24 words, Czech BIP39 words, 25-word Algorand/Monero style list.
        assertSecret(SecretKind.MNEMONIC, List(24) { "zoo" }.joinToString(" "))
        assertSecret(SecretKind.MNEMONIC, List(12) { "abeceda" }.joinToString(" "))
        assertSecret(SecretKind.MNEMONIC, List(25) { "ability" }.joinToString(","))
    }

    @Test
    fun extendedPrivateKeysAreRejectedAlsoInsideDescriptors() {
        val xprv = "xprv9s21ZrQH143K3QTDL4LXw2F7HEK3wJUD2nW2nRk4stbPy6cq3jPPqjiChkVvvNKmPGJxWUtg6LnF5kejMRNNU3TGtRBeJgk33yuGBxrMPHi"
        assertSecret(SecretKind.EXTENDED_PRIVATE_KEY, xprv)
        assertSecret(SecretKind.EXTENDED_PRIVATE_KEY, "wpkh([d34db33f/84'/0'/0']$xprv/0/*)")
        assertSecret(SecretKind.EXTENDED_PRIVATE_KEY, "zprv" + xprv.drop(4))
    }

    @Test
    fun wifAndRelatedKeysAreRejected() {
        assertSecret(SecretKind.WIF_PRIVATE_KEY, "5HueCGU8rMjxEXxiPuD5BDku4MkFqeZyd4dZ1jvhTVqvbTLvyTJ")
        assertSecret(SecretKind.WIF_PRIVATE_KEY, "KwdMAjGmerYanjeui5SHS7JkmpZvVipYvB2LJGU1ZxJwYvP98617")
        assertSecret(SecretKind.WIF_PRIVATE_KEY, "bitcoin:5HueCGU8rMjxEXxiPuD5BDku4MkFqeZyd4dZ1jvhTVqvbTLvyTJ")
        assertSecret(SecretKind.WIF_PRIVATE_KEY, "6PRVWUbkzzsbcVac2qwfssoUJAN1Xhrg6bNk8J7Nzm5H7kxEbn2Nh2ZoGg")
        assertSecret(SecretKind.WIF_PRIVATE_KEY, "S6c56bnXQiBjk9mqSYE7ykVQ7NzrRy")
    }

    @Test
    fun hexKeysAreRejected() {
        val hex = "0c28fca386c7a227600b2fe50b7cae11ec86d3bf1fbe471be89827e19d72aa1d"
        assertSecret(SecretKind.HEX_PRIVATE_KEY, hex)
        assertSecret(SecretKind.HEX_PRIVATE_KEY, hex.uppercase())
        assertSecret(SecretKind.HEX_PRIVATE_KEY, "0x$hex$hex") // 64-byte key with prefix
        assertSecret(SecretKind.HEX_PRIVATE_KEY, "My key, $hex")
    }

    @Test
    fun chainSpecificSecretsAreRejected() {
        assertSecret(SecretKind.SOLANA_SECRET_KEY, List(64) { (it * 3) % 256 }.joinToString(",", "[", "]"))
        assertSecret(SecretKind.XRP_SEED, "snoPBrXtMeMyMHUVTgbuqAfg1SUTb")
        assertSecret(SecretKind.STELLAR_SECRET_SEED, "SBZVMB74Z76QZ3ZOY7UTDFYKMEGKW5XFJEB6PFKBF4UYSSWHG4EDH7PY")
        assertSecret(
            SecretKind.CARDANO_SIGNING_KEY,
            "acct_xsk1azc6gn5zkdprp4gkapmhdckykphjl62rm9224699ut5z6xcaa9p4hv5hmjfgcrzk72tnsqh6dw0njekdjpsv8nv5h5hk6lpd4ag62zenwhzqs205kfurd7kgs8fm5gx4l4j8htutwj060kyp5y5kgw55qc8lsltd",
        )
        assertSecret(SecretKind.SUI_PRIVATE_KEY, "suiprivkey1qzwant3kaegmjy4qxex93s0jzvemekkjmyv3r2sjwgnv2y479pgsywhveae")
        assertSecret(SecretKind.APTOS_PRIVATE_KEY, "ed25519-priv-0x" + "11".repeat(32))
        assertSecret(SecretKind.TEZOS_SECRET_KEY, "edsk3QoqBuvdamxouPhin7swCvkQNgq4jP5KZPbwWNnwdZpSpJiEbq")
        assertSecret(SecretKind.ENCRYPTED_KEYSTORE, """{"crypto":{"ciphertext":"ab","cipherparams":{},"kdf":"scrypt"}}""")
    }

    @Test
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    fun formatsReportedByChainAdaptersAreRejected() {
        val b64 = kotlin.io.encoding.Base64
        assertSecret(SecretKind.BASE64_PRIVATE_KEY, b64.encode(ByteArray(64) { it.toByte() })) // Algorand sk
        assertSecret(SecretKind.BASE64_PRIVATE_KEY, b64.encode(ByteArray(33) { (it * 7).toByte() })) // legacy Sui keystore
        assertSecret(
            SecretKind.ENCRYPTED_KEYSTORE,
            listOf("-----BEGIN TENDERMINT PRIVATE KEY-----", "kdf: bcrypt", "abc", "-----END TENDERMINT PRIVATE KEY-----").joinToString("\n"),
        )
        assertSecret(SecretKind.ENCRYPTED_KEYSTORE, """{"encoded":"abc","encoding":{"content":["pkcs8","sr25519"],"type":["scrypt"]},"address":"x"}""")
        assertSecret(SecretKind.EXTENDED_PRIVATE_KEY, "kprv" + "A".repeat(107))
        assertSecret(SecretKind.NEAR_PRIVATE_KEY, "secp256k1:" + "5".repeat(44))
    }

    @Test
    fun publicAddressesAndKeysAreNotSecrets() {
        listOf(
            "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa",
            "3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy",
            "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq",
            "bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr",
            "bitcoin:bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq?amount=0.1&label=Donation",
            "xpub6BosfCnifzxcFwrSzQiqu2DBVTshkCXacvNsWGYJVVhhawA7d4R5WSWGFNbi8Aw6ZRc1brxMyWMzG3DSSSSoekkudhUd9yLb6qx39T9nMdj",
            "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs",
            "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
            "ethereum:0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed@1",
            "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU",
            "rHb9CJAWyB4rj91VRWn96DkukG4bwdtyTh",
            "GA7QYNF7SOWQ3GLR2BGMZEHXAVIRZA4KVWLTJJFC7MGXUA74P7UJVSGZ",
            "TLyqzVGLV1srkB7dToTAEqgDSfPtXRJZYH",
            "EQDtFpEwcFAEcRe5mLVh2N6C0x-_hJEM7W61_JLnSF74p4q2",
            "addr1qx2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzer3n0d3vllmyqwsx5wktcd8cc3sq835lu7drv2xwl2wywfgse35a3x",
            "stake1u9ylzsgxaa6xctf4juup682ar3juj85n8tx3hthnljg47zctvm3rc",
            "cosmos1vqpjljwsynsn58dugz0w8ut7kun7t8ls2qkmsq",
            "My cold storage",
            "",
        ).forEach(::assertClean)
    }

    @Test
    fun labelledCsvRowsAreCheckedTokenByToken() {
        assertClean("Savings, bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq, Cold storage")
        assertSecret(SecretKind.WIF_PRIVATE_KEY, "Savings, 5HueCGU8rMjxEXxiPuD5BDku4MkFqeZyd4dZ1jvhTVqvbTLvyTJ")
    }
}
