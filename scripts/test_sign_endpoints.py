#!/usr/bin/env python3
"""Tests for sign_endpoints.py. Uses BIP-340 test keys only, never the real signing key.

    python scripts/test_sign_endpoints.py
"""

import base64
import contextlib
import csv
import hashlib
import io
import json
import sys
import tempfile
import time
import unittest
import urllib.error
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

import sign_endpoints as tool  # noqa: E402

VECTORS = Path(__file__).resolve().parent / "testdata" / "bip340-test-vectors.csv"
TEST_SECRET = "0000000000000000000000000000000000000000000000000000000000000003"
TEST_PUB = "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9"
SOURCE = {
    "version": 2,
    "expires_in_days": 30,
    "endpoints": [{"chain": "bitcoin", "protocol": "esplora", "urls": ["https://a.test/api"]}],
}


class Bip340Vectors(unittest.TestCase):
    def rows(self):
        with VECTORS.open(newline="", encoding="ascii") as handle:
            return list(csv.DictReader(handle))

    def test_all_official_vectors_are_present(self):
        self.assertEqual(19, len(self.rows()))

    def test_signing_vectors(self):
        signing = [row for row in self.rows() if row["secret key"]]
        self.assertEqual(8, len(signing))
        for row in signing:
            with self.subTest(index=row["index"]):
                secret = bytes.fromhex(row["secret key"])
                self.assertEqual(row["public key"].lower(), tool.public_key(secret).hex())
                signature = tool.schnorr_sign(bytes.fromhex(row["message"]), secret, bytes.fromhex(row["aux_rand"]))
                self.assertEqual(row["signature"].lower(), signature.hex())

    def test_verification_vectors(self):
        for row in self.rows():
            with self.subTest(index=row["index"], comment=row["comment"]):
                result = tool.schnorr_verify(bytes.fromhex(row["message"]), bytes.fromhex(row["public key"]), bytes.fromhex(row["signature"]))
                self.assertEqual(row["verification result"] == "TRUE", result)


class Tool(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.dir = Path(directory.name)
        self.key = self.dir / "test.key"
        self.key.write_text(TEST_SECRET + "\n", encoding="ascii")
        self.source = self.dir / "source.json"
        self.source.write_text(json.dumps(SOURCE), encoding="utf-8")
        self.output = self.dir / "endpoints.json"
        self.published = {}
        self.fetched = []

    def run_tool(self, *extra, key=None, pubkey=TEST_PUB):
        args = ["--key", str(key or self.key), "--source", str(self.source), "--output", str(self.output), "--pubkey", pubkey, *extra]
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = tool.main(args, fetch=self.fetch)
        return code, out.getvalue() + err.getvalue()

    def fetch(self, url):
        """Stands in for the network: nothing is published unless a test says so."""
        self.fetched.append(url)
        answer = self.published.get(url)
        if isinstance(answer, Exception):
            raise answer
        return answer

    def signed_file(self, version):
        payload = tool.build_payload({**SOURCE, "version": version}, int(time.time()))
        return tool.build_envelope(payload, bytes.fromhex(TEST_SECRET), bytes(32))

    def test_sign_then_check(self):
        self.assertEqual((0, True), (self.run_tool()[0], self.output.exists()))
        self.assertEqual(0, self.run_tool("--check")[0])

    def test_signature_covers_sha256_of_the_exact_payload_bytes(self):
        self.run_tool()
        envelope = json.loads(self.output.read_text())
        payload = base64.b64decode(envelope["payload"])
        self.assertTrue(tool.schnorr_verify(hashlib.sha256(payload).digest(), bytes.fromhex(TEST_PUB), bytes.fromhex(envelope["signature"])))
        decoded = json.loads(payload)
        self.assertEqual((1, 2), (decoded["format"], decoded["version"]))
        self.assertEqual(SOURCE["endpoints"], decoded["endpoints"])

    def test_check_rejects_tampered_payload(self):
        self.run_tool()
        envelope = json.loads(self.output.read_text())
        payload = base64.b64decode(envelope["payload"]).replace(b"a.test", b"b.test")
        envelope["payload"] = base64.b64encode(payload).decode()
        self.output.write_text(json.dumps(envelope))
        code, message = self.run_tool("--check")
        self.assertEqual(1, code)
        self.assertIn("Bad signature", message)

    def test_check_rejects_other_key(self):
        self.run_tool()
        self.assertEqual(1, self.run_tool("--check", pubkey=tool.PINNED_PUBLIC_KEY)[0])

    def test_check_rejects_file_out_of_date_with_source(self):
        self.run_tool()
        self.source.write_text(json.dumps({**SOURCE, "version": 3}), encoding="utf-8")
        code, message = self.run_tool("--check")
        self.assertEqual(1, code)
        self.assertIn("does not match", message)

    def test_refuses_key_that_does_not_match_the_pinned_one(self):
        code, _ = self.run_tool(pubkey=tool.PINNED_PUBLIC_KEY)
        self.assertEqual(1, code)
        self.assertFalse(self.output.exists())

    def test_refuses_version_that_apps_would_reject(self):
        self.run_tool()
        code, message = self.run_tool()
        self.assertEqual(1, code)
        self.assertIn("Bump", message)

    def test_compares_with_both_published_mirrors(self):
        self.assertEqual(0, self.run_tool()[0])
        self.assertEqual(list(tool.PUBLISHED_URLS), self.fetched)

    def test_refuses_version_not_above_the_published_one_without_a_local_file(self):
        for mirror in tool.PUBLISHED_URLS:
            with self.subTest(mirror=mirror):
                self.published = {mirror: self.signed_file(SOURCE["version"])}
                code, message = self.run_tool()
                self.assertEqual(1, code)
                self.assertIn("Bump", message)
                self.assertFalse(self.output.exists())

    def test_signs_above_the_published_version(self):
        self.published = {tool.PUBLISHED_URLS[0]: self.signed_file(SOURCE["version"] - 1)}
        self.assertEqual(0, self.run_tool()[0])
        self.assertTrue(self.output.exists())

    def test_refuses_when_no_mirror_can_be_read(self):
        self.published = {url: tool.EndpointsError("down") for url in tool.PUBLISHED_URLS}
        code, message = self.run_tool()
        self.assertEqual(1, code)
        self.assertIn("--offline", message)
        self.assertFalse(self.output.exists())

    def test_one_readable_mirror_is_enough(self):
        self.published = {tool.PUBLISHED_URLS[0]: tool.EndpointsError("down")}
        code, message = self.run_tool()
        self.assertEqual(0, code)
        self.assertIn("Warning", message)

    def test_offline_skips_the_comparison(self):
        self.published = {url: tool.EndpointsError("down") for url in tool.PUBLISHED_URLS}
        self.assertEqual(0, self.run_tool("--offline")[0])
        self.assertEqual([], self.fetched)

    def test_published_file_with_a_bad_signature_does_not_count(self):
        forged = json.loads(self.signed_file(99))
        forged["signature"] = "00" * 64
        self.published = {tool.PUBLISHED_URLS[0]: json.dumps(forged).encode()}
        code, message = self.run_tool()
        self.assertEqual(0, code)
        self.assertIn("no file signed", message)

    def test_download_treats_a_missing_file_as_not_published(self):
        def not_found(request, timeout):
            raise urllib.error.HTTPError(request.full_url, 404, "Not Found", None, None)

        with mock.patch("urllib.request.urlopen", not_found):
            self.assertIsNone(tool.download("https://a.test/endpoints.json"))

    def test_download_reports_other_failures(self):
        def refused(request, timeout):
            raise urllib.error.URLError("refused")

        with mock.patch("urllib.request.urlopen", refused), self.assertRaises(tool.EndpointsError):
            tool.download("https://a.test/endpoints.json")

    def test_refuses_an_expiry_apps_would_ignore(self):
        self.source.write_text(json.dumps({**SOURCE, "expires_in_days": tool.MAX_VALIDITY_DAYS + 1}), encoding="utf-8")
        code, message = self.run_tool()
        self.assertEqual(1, code)
        self.assertIn("expires_in_days", message)
        self.assertFalse(self.output.exists())

    def test_check_rejects_an_expiry_apps_would_ignore(self):
        payload = json.dumps(
            {"format": 1, "version": 2, "expires": int(time.time()) + (tool.MAX_VALIDITY_DAYS + 1) * tool.SECONDS_PER_DAY, "endpoints": SOURCE["endpoints"]}
        ).encode()
        self.output.write_bytes(tool.build_envelope(payload, bytes.fromhex(TEST_SECRET), bytes(32)))
        code, message = self.run_tool("--check")
        self.assertEqual(1, code)
        self.assertIn("ignore", message)

    def test_payload_says_when_it_was_signed(self):
        decoded = json.loads(tool.build_payload(SOURCE, 1_000))
        self.assertEqual(1_000, decoded["signed"])

    def test_refuses_non_https_and_query_urls(self):
        for url in ("http://a.test", "https://user:pw@a.test", "https://a.test/?key=1"):
            with self.subTest(url=url):
                bad = {**SOURCE, "endpoints": [{"chain": "bitcoin", "protocol": "esplora", "urls": [url]}]}
                self.source.write_text(json.dumps(bad), encoding="utf-8")
                self.assertEqual(1, self.run_tool()[0])
                self.assertFalse(self.output.exists())

    def test_key_file_formats(self):
        secret = bytes.fromhex(TEST_SECRET)
        raw = self.dir / "raw.key"
        raw.write_bytes(secret)
        self.assertEqual(secret, tool.load_secret(raw, TEST_PUB))
        labelled = self.dir / "labelled.key"
        labelled.write_text(f'{{"public": "{TEST_PUB}", "secret": "0x{TEST_SECRET}"}}\n', encoding="ascii")
        self.assertEqual(secret, tool.load_secret(labelled, TEST_PUB))

    def test_never_prints_the_key(self):
        outcomes = [
            self.run_tool(),
            self.run_tool("--check"),
            self.run_tool(pubkey=tool.PINNED_PUBLIC_KEY),
        ]
        malformed = self.dir / "malformed.key"
        malformed.write_text(TEST_SECRET[:-2] + "zz", encoding="ascii")
        outcomes.append(self.run_tool(key=malformed))
        for _, message in outcomes:
            self.assertNotIn(TEST_SECRET, message)
            self.assertNotIn(TEST_SECRET[:-2], message)


class Published(unittest.TestCase):
    def test_source_is_acceptable_for_signing(self):
        source = json.loads(tool.DEFAULT_SOURCE.read_text(encoding="utf-8"))
        tool.build_payload(source, 1)


if __name__ == "__main__":
    unittest.main()
