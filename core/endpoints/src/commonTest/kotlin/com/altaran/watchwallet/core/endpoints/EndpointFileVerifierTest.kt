package com.altaran.watchwallet.core.endpoints

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EndpointFileVerifierTest {
    private fun rejection(envelope: String) = assertIs<EndpointFileResult.Rejected>(verifier.open(envelope)).reason

    private fun entries(vararg items: EndpointEntry) =
        assertIs<EndpointFileResult.Valid>(verifier.open(signed(entries = items.toList()))).file.entries

    @Test
    fun validSignatureOpens() {
        val file = assertIs<EndpointFileResult.Valid>(verifier.open(signed(version = 9))).file
        assertEquals(9, file.version)
        assertEquals(LATER, file.expiresAtSeconds)
        assertEquals(listOf(entry()), file.entries)
    }

    /** Written by scripts/sign_endpoints.py, so both implementations are known to agree. */
    @Test
    fun acceptsFileFromThePythonTool() {
        val file = assertIs<EndpointFileResult.Valid>(verifier.open(PYTHON_ENVELOPE)).file
        assertEquals(7, file.version)
        assertEquals(listOf(entry(urls = arrayOf("https://python.test/api"))), file.entries)
    }

    @Test
    fun pinsTheOwnersKey() {
        assertEquals("d518716d260dec9a323611396e9917f219a8ef02824b877975638af91e0f8866", EndpointFileVerifier.PINNED_PUBLIC_KEY)
        val result = EndpointFileVerifier().open(signed())
        assertEquals(EndpointRejection.BAD_SIGNATURE, assertIs<EndpointFileResult.Rejected>(result).reason)
    }

    @Test
    fun tamperedPayloadIsRejected() {
        val original = payload()
        val tampered = original.replace("new.test", "bad.test")
        assertEquals(EndpointRejection.BAD_SIGNATURE, rejection(envelope(tampered, signature(original))))
    }

    @Test
    fun wrongKeyIsRejected() {
        val text = payload()
        assertEquals(EndpointRejection.BAD_SIGNATURE, rejection(envelope(text, signature(text, TestKeys.OTHER_SECRET))))
    }

    @Test
    fun unsignedAndMalformedFilesAreRejected() {
        val encoded = Base64.encode(payload().encodeToByteArray())
        assertEquals(EndpointRejection.MALFORMED, rejection(payload()))
        assertEquals(EndpointRejection.MALFORMED, rejection("not json"))
        assertEquals(EndpointRejection.MALFORMED, rejection("""{"payload":"$encoded"}"""))
        assertEquals(EndpointRejection.MALFORMED, rejection("""{"payload":"$encoded","signature":"zz"}"""))
        assertEquals(EndpointRejection.BAD_SIGNATURE, rejection("""{"payload":"$encoded","signature":"abcd"}"""))
    }

    @Test
    fun oversizeIsRejected() {
        assertEquals(EndpointRejection.OVERSIZE, rejection(" ".repeat(EndpointFileVerifier.MAX_ENVELOPE_BYTES + 1)))
    }

    @Test
    fun signedGarbageAndUnknownFormatAreRejected() {
        assertEquals(EndpointRejection.BAD_PAYLOAD, rejection(envelope("not json")))
        assertEquals(EndpointRejection.BAD_PAYLOAD, rejection(envelope(payload(format = 2))))
        assertEquals(EndpointRejection.BAD_PAYLOAD, rejection(envelope(payload(version = 0))))
    }

    @Test
    fun nonHttpsUrlsAreIgnoredPerUrlAndPerEntry() {
        val kept = entry("xrp", "xrpl", "https://ok.test")
        val mixed = entry(urls = arrayOf("http://plain.test/api", "https://ok.test/api"))
        val onlyBad = entry("solana", "solana-rpc", "http://plain.test", "ftp://x.test", "file:///etc/passwd", "https://")
        assertEquals(
            listOf(entry(urls = arrayOf("https://ok.test/api")), kept),
            entries(mixed, onlyBad, kept),
        )
    }

    @Test
    fun urlsWithCredentialsOrQueryAreIgnored() {
        val bad = entry(urls = arrayOf("https://user:pw@a.test", "https://a.test/?key=1", "https://a.test/#x", "https://a b.test", "HTTPS://a.test"))
        assertEquals(emptyList(), entries(bad))
    }

    @Test
    fun trailingSlashIsRemoved() {
        assertEquals(listOf(entry(urls = arrayOf("https://a.test/api"))), entries(entry(urls = arrayOf("https://a.test/api/"))))
    }

    @Test
    fun unknownProtocolIsIgnored() {
        val kept = entry()
        assertEquals(listOf(kept), entries(entry(protocol = "script-runner"), kept))
    }

    @Test
    fun unknownChainIsIgnored() {
        val kept = entry()
        assertEquals(listOf(kept), entries(entry(chain = "dogwifcoin"), entry(chain = "evm:999999", protocol = "evm-rpc"), kept))
    }

    @Test
    fun protocolOfAnotherChainIsIgnored() {
        assertEquals(emptyList(), entries(entry(protocol = "evm-rpc"), entry(chain = "evm:1", protocol = "esplora")))
    }

    @Test
    fun blockscoutMayBeAddedToAnEvmNetworkWithoutOne() {
        val added = entry("evm:56", "blockscout", "https://bsc-scout.test")
        assertEquals(listOf(added), entries(added))
    }

    @Test
    fun malformedEntryDoesNotDiscardTheOthers() {
        val text = payload().replace("\"endpoints\":[", "\"endpoints\":[{\"chain\":1},\"x\",")
        val file = assertIs<EndpointFileResult.Valid>(verifier.open(envelope(text))).file
        assertEquals(listOf(entry()), file.entries)
    }

    @Test
    fun aLowVersionIsRefusedByTheFloorOfThisBuild() {
        val floored = EndpointFileVerifier(TestKeys.PUBLIC, recoveryKeyHex = null, minVersion = 10)
        assertEquals(EndpointRejection.STALE_VERSION, assertIs<EndpointFileResult.Rejected>(floored.open(signed(version = 9))).reason)
        assertEquals(10, assertIs<EndpointFileResult.Valid>(floored.open(signed(version = 10))).file.version)
    }

    @Test
    fun theFloorAlsoAppliesToTheRecoveryKey() {
        val floored = EndpointFileVerifier(TestKeys.PUBLIC, TestKeys.OTHER_PUBLIC, minVersion = 10)
        assertEquals(EndpointRejection.STALE_VERSION, assertIs<EndpointFileResult.Rejected>(floored.open(signedByRecovery(version = 9))).reason)
    }

    @Test
    fun theBuildsVersionFloorIsAtLeastOne() {
        assertEquals(true, EndpointFileVerifier.MIN_ACCEPTED_VERSION >= 1)
    }

    @Test
    fun aPinnedRecoveryKeyOpensItsFiles() {
        val file = assertIs<EndpointFileResult.Valid>(recoverableVerifier.open(signedByRecovery())).file
        assertEquals(true, file.signedByRecoveryKey)
        assertEquals(false, assertIs<EndpointFileResult.Valid>(recoverableVerifier.open(signed())).file.signedByRecoveryKey)
    }

    @Test
    fun aRecoverySignatureCountsOnlyWhereTheKeyIsPinned() {
        assertEquals(EndpointRejection.BAD_SIGNATURE, rejection(signedByRecovery()))
    }

    @Test
    fun aThirdKeyIsRefusedEvenWithARecoveryKeyPinned() {
        val other = EndpointFileVerifier(TestKeys.PUBLIC, recoveryKeyHex = TestKeys.PUBLIC)
        assertEquals(EndpointRejection.BAD_SIGNATURE, assertIs<EndpointFileResult.Rejected>(other.open(signedByRecovery())).reason)
    }

    @Test
    fun aMalformedRecoveryKeyVerifiesNothing() {
        val broken = EndpointFileVerifier(TestKeys.PUBLIC, recoveryKeyHex = "abcd")
        assertEquals(EndpointRejection.BAD_SIGNATURE, assertIs<EndpointFileResult.Rejected>(broken.open(signedByRecovery())).reason)
    }

    @Test
    fun theSigningDateIsReadWhenPresent() {
        val text = payload().replace("\"format\":1,", "\"format\":1,\"signed\":1700000000,")
        assertEquals(1_700_000_000, assertIs<EndpointFileResult.Valid>(verifier.open(envelope(text))).file.signedAtSeconds)
        assertEquals(null, assertIs<EndpointFileResult.Valid>(verifier.open(signed())).file.signedAtSeconds)
    }

    private companion object {
        // python scripts/sign_endpoints.py with the BIP-340 test key and a far-future expiry.
        const val PYTHON_ENVELOPE =
            "{\"payload\":\"ewogImZvcm1hdCI6IDEsCiAidmVyc2lvbiI6IDcsCiAiZXhwaXJlcyI6IDQ5NDcyNTI1NjAsCiAiZW5kcG9pb" +
                "nRzIjogWwogIHsKICAgImNoYWluIjogImJpdGNvaW4iLAogICAicHJvdG9jb2wiOiAiZXNwbG9yYSIsCiAgICJ1cmxzIjogWwogI" +
                "CAgImh0dHBzOi8vcHl0aG9uLnRlc3QvYXBpIgogICBdCiAgfQogXQp9\",\"signature\":\"46c43065a6f8bf6c30f7fe8bdf" +
                "cb4b656cbc6ea1040f023430da0aae163099dd0fb090106eb63b7a56d3db5689d5f130c8b9b1c7d9b1b401961caf82ed571c" +
                "44\"}"
    }
}
