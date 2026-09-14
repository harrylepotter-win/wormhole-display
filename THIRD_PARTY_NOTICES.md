# Third-party notices

Wormhole Display is Copyright © 2026 Piotr Godlewski and licensed under the GNU
General Public License v3.0 (see [LICENSE](LICENSE)). It includes the following
third-party components, which remain under their own copyrights and licenses.

| Component | Version | License | Location | Upstream |
|---|---|---|---|---|
| UxPlay mirroring core (RPiPlay-derived) | vendored, with [local patches](docs/uxplay-patches.md) | [GPL-3.0](app/src/main/cpp/uxplay/LICENSE.GPLv3); `lib/` core LGPL-2.1-or-later | `app/src/main/cpp/uxplay/` | https://github.com/FDH2/UxPlay |
| llhttp (bundled with UxPlay) | vendored | MIT | `app/src/main/cpp/uxplay/lib/llhttp/` | https://github.com/nodejs/llhttp |
| mdnsd (bundled with UxPlay) | vendored | BSD-style | `app/src/main/cpp/uxplay/lib/mdnsd/` | via UxPlay |
| OpenSSL `libcrypto` (prebuilt static library + headers, arm64) | 3.0.16 | [Apache-2.0](app/src/main/cpp/deps/openssl/LICENSE.txt) | `app/src/main/cpp/deps/openssl/` | https://www.openssl.org/ |
| libplist (prebuilt static library + header, arm64) | 2.6.0 | [LGPL-2.1-or-later](app/src/main/cpp/deps/plist/COPYING.LESSER) | `app/src/main/cpp/deps/plist/` | https://github.com/libimobiledevice/libplist |

Per-file copyright and license headers in the vendored sources remain
authoritative. Full license texts are linked in the table. The OpenSSL and
libplist texts are the upstream files from the `openssl-3.0.16` and `2.6.0`
release tags.

The prebuilt OpenSSL and libplist libraries are built from unmodified upstream
release tarballs by `scripts/build-deps.sh`, which records the exact download
URLs and build configuration.
