# UxPlay vendor patch inventory

`app/src/main/cpp/uxplay/lib/` is a vendored copy of UxPlay's C core. Keep
downstream changes narrowly scoped and listed here so upgrades remain diffable.
Patched code is marked "Android port" in the source (the public-key getter is
marked "Android JNI addition").

| File | Change |
|---|---|
| `raop.c` / `raop.h` | `raop_get_pk_str` public-key getter; partial-initialization shutdown guard (no `httpd_stop(NULL)`). |
| `raop_rtp_mirror.c` | H.264/HEVC parameter-set bounds checks; clear previous prepend state before replacing configuration; validate AVCC length/header availability before reads; allocation cap; EOF/allocation/streaming-report cleanup; worker-join ownership (the mirror worker no longer stops/joins itself). |
| `httpd.c` | Join a finished but unjoined worker before freeing it. |
| `byteutils.c` | Alignment-safe (`memcpy`) packet field loads/stores. |
| `mdnsd/dnssd_mdnsd.c` | Build a unique mDNS hostname from `hwaddr` instead of `gethostname()`, which returns "localhost" on Android and caused LAN collisions and misrouted connections across multiple receivers. |

These are deliberate correctness changes. No upstream-version claim is made;
reconcile each with upstream during the next controlled vendor update. The UxPlay
license is preserved and the `dns_sd` (Bonjour/Avahi) backend stays excluded.

## Upgrading UxPlay

1. Re-vendor `lib/` (keep `mdnsd/`, exclude `dns_sd/`).
2. Re-apply `raop_get_pk_str`, then check each change above against upstream.
3. Run the regression checks:

```sh
./gradlew :app:testDebugUnitTest
GUARD_MALLOC=1 ./scripts/test-native.sh
./scripts/build.sh
```

The native tests (`tests/native/`) drive the real `raop_rtp_mirror.c` loop over
loopback sockets with UBSan in fail-fast mode; `GUARD_MALLOC=1` adds macOS strict
Guard Malloc. Crypto, NTP and plist are stubbed, so they cover parser and lifetime
paths, not live sender compatibility. On a host with a working ASan runtime,
`SANITIZERS=address,undefined ./scripts/test-native.sh` enables ASan as well.
