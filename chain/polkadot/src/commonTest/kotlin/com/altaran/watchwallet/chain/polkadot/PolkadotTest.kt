package com.altaran.watchwallet.chain.polkadot

import com.altaran.watchwallet.chain.common.Hex
import com.altaran.watchwallet.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolkadotTest {
    // Well-known development account "Alice" (public key only).
    private val aliceId = "d43593c715fdd31c61141abd04a99fd6822c8558854ccde39a5684e7a56da27d"
    private val aliceGeneric = "5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY"
    private val alicePolkadot = "15oF4uVJwmo4TdGW7VfQxNLavjCXviqxT9S1MgbjMNHr6Sp5"
    private val aliceKusama = "HNZata7iMYWmk5RvZRTiAsSDhV8366zq2YGb3tLH5Upf74F"

    @Test
    fun addressValidation() {
        val cases = listOf(
            alicePolkadot to PolkadotChains.POLKADOT.id,
            aliceKusama to PolkadotChains.KUSAMA.id,
            aliceGeneric to null, // generic prefix needs an explicit network
            alicePolkadot.dropLast(1) + "6" to null, // wrong checksum
            alicePolkadot.dropLast(3) to null, // wrong length
            "1LqBGSKuX5yYUonjxT5qGfpUsXKYYWeabA" to null, // Bitcoin
        )
        cases.forEach { (input, chain) -> assertEquals(chain, PolkadotAddresses.parse(input)?.chainId, input) }
        assertEquals(aliceId, Hex.encode(assertNotNull(PolkadotAddresses.decode(aliceGeneric)).accountId))
        assertEquals(alicePolkadot, PolkadotAddresses.parseGeneric(aliceGeneric, PolkadotChains.POLKADOT)?.address)
        assertEquals(aliceKusama, PolkadotAddresses.encode(Hex.decode(aliceId)!!, 2))
    }

    @Test
    fun xxHashAndStorageKey() {
        assertEquals(0xEF46DB3751D8E999uL, XxHash64.hash(ByteArray(0), 0uL))
        assertEquals(
            "26aa394eea5630e07c48ae0c9558cef7",
            Hex.encode(SubstrateStorage.twox128("System".encodeToByteArray())),
        )
        assertEquals(
            "b99d880ec681799c0cf30e8886371da9",
            Hex.encode(SubstrateStorage.twox128("Account".encodeToByteArray())),
        )
        assertEquals(
            "26aa394eea5630e07c48ae0c9558cef7b99d880ec681799c0cf30e8886371da9de1e86a9a8c739864cf3cc5ec2bea59f$aliceId",
            Hex.encode(SubstrateStorage.systemAccountKey(Hex.decode(aliceId)!!)),
        )
        // 40 bytes exercise the 32-byte stripe loop as well as the tail.
        assertEquals(
            XxHash64.hash(
                ByteArray(40) {
                    it.toByte()
                },
                0uL,
            ),
            XxHash64.hash(ByteArray(40) { it.toByte() }, 0uL),
        )
    }

    private fun u128(value: Long) = Hex.encode(
        ByteArray(16) {
            if (it <
                8
            ) {
                ((value shr (8 * it)) and 0xFF).toByte()
            } else {
                0
            }
        },
    )

    @Test
    fun syncDecodesAccountInfo() = runTest {
        // nonce 5, consumers 1, providers 1, sufficients 0; free 12.5 DOT, reserved 2 DOT, frozen 10 DOT, flags.
        val info = "0x05000000" + "01000000" + "01000000" + "00000000" +
            u128(125_000_000_000) + u128(20_000_000_000) + u128(100_000_000_000) + u128(0)
        // A leftover of 1 DOT stayed on the relay chain after the Asset Hub migration.
        val relayLeftover = "0x00000000" + "00000000" + "01000000" + "00000000" +
            u128(10_000_000_000) + u128(0) + u128(0) + u128(0)
        val engine = MockEngine { request ->
            val body = request.body.toByteArray().decodeToString()
            assertTrue("state_getStorage" in body && aliceId in body)
            val result = if ("asset-hub" in request.url.host) info else relayLeftover
            respond(
                """{"jsonrpc":"2.0","result":"$result","id":1}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val adapter = PolkadotAdapter(createHttpClient(engine = engine))
        val target = assertNotNull(PolkadotAddresses.parse(alicePolkadot))
        val result = adapter.sync(target, emptySet())
        assertEquals("15.5", result.balances.single().amount.toPlainString(), "Asset Hub free + reserved + relay leftover")
        assertEquals("10", adapter.lockedBalance(target).toPlainString())
        assertTrue(result.transactions.isEmpty())
        assertFalse(adapter.supportsHistory)
    }

    @Test
    fun missingAccountIsInactive() = runTest {
        val engine = MockEngine {
            respond(
                """{"jsonrpc":"2.0","result":null,"id":1}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val probe = PolkadotAdapter(
            createHttpClient(engine = engine),
        ).probe(assertNotNull(PolkadotAddresses.parse(aliceKusama)))
        assertFalse(probe.hasActivity)
        assertNull(probe.transactionCount)
    }
}
