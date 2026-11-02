package com.altaran.watchwallet.core.network

import io.ktor.http.encodeURLPathPart

/**
 * [value] as one segment of a URL path. Addresses come from the database, where a backup imported
 * by an older version can have put anything, and token ids and cursors come from servers, so none
 * of them may be able to change which path of a host a request goes to.
 *
 * Valid addresses and ids contain nothing to encode and stay as they are, `kaspa:qq...` included.
 * Empty and dot segments are refused: encoding does not help, as servers resolve `%2E%2E` as well.
 *
 * @throws IllegalArgumentException without quoting the value, which may be sensitive.
 */
fun pathSegment(value: String): String {
    require(value.isNotEmpty() && value != "." && value != "..") { "Not a valid path segment" }
    return value.encodeURLPathPart()
}
