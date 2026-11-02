package com.altaran.watchwallet.feature.donate

/**
 * The only two donation addresses of the project.
 *
 * Kept in this single file so they are easy to audit. The same addresses are listed in
 * README.md; users can verify that both match. Any change here must update the README too.
 */
object DonationAddresses {
    const val BTC_DONATION_ADDRESS = "bc1q474j9j3uc4kk48uphhvwd284v9jzmkkpf0e583"
    const val ETH_DONATION_ADDRESS = "0x45A92396902ED0F49ae39f2F94c3f8f084501656"
}
