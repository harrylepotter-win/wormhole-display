/* Exercises the real vendored receive loop on loopback; crypto/NTP/plist are isolated stubs.
 * No real sender, JNI, or running receiver is touched. */
#include <stdatomic.h>
#include "raop_rtp_mirror.c"

mirror_buffer_t *mirror_buffer_init(logger_t *l, const unsigned char *key) { return (mirror_buffer_t *) calloc(1, 1); }
void mirror_buffer_init_aes(mirror_buffer_t *b, const uint64_t *id) {}
void mirror_buffer_decrypt(mirror_buffer_t *b, unsigned char *in, unsigned char *out, int len) { memcpy(out, in, len); }
void mirror_buffer_destroy(mirror_buffer_t *b) { free(b); }
uint64_t raop_ntp_timestamp_to_nano_seconds(uint64_t n, bool epoch) { return n; }
uint64_t raop_ntp_get_local_time(void) { return 0; }
uint64_t raop_ntp_convert_remote_time(raop_ntp_t *n, uint64_t t) { return t; }
void raop_ntp_set_video_arrival_offset(raop_ntp_t *n, const uint64_t *offset) {}
char *utils_data_to_string(const unsigned char *d, int len, int cols) { return strdup(""); }
plist_err_t plist_from_bin(const char *data, uint32_t length, plist_t *out) { *out = NULL; return PLIST_ERR_SUCCESS; }
plist_err_t plist_to_xml(plist_t p, char **out, uint32_t *length) { *out = strdup(""); *length = 0; return PLIST_ERR_SUCCESS; }

void plist_free(plist_t p) {}

static atomic_int videos, exited, allow_exit, stopped;
static bool test_hevc;
static void video(void *cls, raop_ntp_t *n, video_decode_struct *d) { atomic_fetch_add(&videos, 1); }
static int set_codec(void *cls, video_codec_t codec) { return codec == VIDEO_CODEC_H265 && !test_hevc ? -1 : 0; }
static void running(void *cls, bool value) {
    if (!value) {
        atomic_store(&exited, 1);
        while (!atomic_load(&allow_exit)) usleep(1000);
    }
}
static void noop(void *cls) {}
static void reset(void *cls, reset_type_t type) {}
static void conn_reset_cb(void *cls, int reason) {}
static void *stop_worker(void *arg) { raop_rtp_mirror_stop(arg); atomic_store(&stopped, 1); return NULL; }
static void wait_for(atomic_int *value) {
    for (int i = 0; i < 2000 && !atomic_load(value); ++i) usleep(1000);
    assert(atomic_load(value));
}
static void send_all(int fd, const void *data, size_t len) {
    const char *p = data;
    while (len) { ssize_t n = send(fd, p, len, 0); assert(n > 0); p += n; len -= n; }
}
static void packet(int fd, unsigned char type, const unsigned char *data, unsigned size) {
    unsigned char header[128] = {0};
    header[0] = size; header[1] = size >> 8; header[2] = size >> 16; header[3] = size >> 24; header[4] = type;
    send_all(fd, header, sizeof(header));
    if (size) send_all(fd, data, size);
}

int main(void) {
    signal(SIGPIPE, SIG_IGN);
    for (int iteration = 0; iteration < 50; ++iteration) {
        atomic_store(&videos, 0); atomic_store(&exited, 0); atomic_store(&allow_exit, 0); atomic_store(&stopped, 0);
        logger_t *log = logger_init(); logger_set_level(log, LOGGER_EMERG);
        raop_callbacks_t callbacks = {0};
        callbacks.video_process = video; callbacks.video_set_codec = set_codec;
        callbacks.mirror_video_running = running; callbacks.video_pause = noop;
        callbacks.video_reset = reset; callbacks.conn_reset = conn_reset_cb;
        unsigned char key[16] = {0}; const char *remote = "127.0.0.1";
        raop_rtp_mirror_t *mirror = raop_rtp_mirror_init(log, &callbacks, (raop_ntp_t *) 1, remote, 4, key);
        assert(mirror);
        unsigned short port = 0;
        raop_rtp_mirror_start(mirror, &port, 0);
        int fd = socket(AF_INET, SOCK_STREAM, 0); assert(fd >= 0);
        struct sockaddr_in address = {.sin_family = AF_INET, .sin_port = htons(port), .sin_addr.s_addr = htonl(INADDR_LOOPBACK)};
        assert(connect(fd, (struct sockaddr *) &address, sizeof(address)) == 0);
        // Valid config followed by truncated replacement used to leave prepend=true with a freed SPS pointer.
        unsigned char config[13] = {1,0,0,0,0,0,0,1,0x67,1,0,1,0x68};
        unsigned char picture[8] = {0,0,0,2,0x65,0x80};
        test_hevc = iteration >= 30 && iteration < 36;
        if (test_hevc) {
            unsigned char hevc[140] = {0};
            memcpy(hevc + 4, "hvc1", 4);
            hevc[117] = 0xa0; hevc[119] = 1; hevc[121] = 1;
            hevc[123] = 0xa1; hevc[125] = 1; hevc[127] = 1;
            hevc[129] = 0xa2; hevc[131] = 1; hevc[133] = 1;
            const unsigned sizes[] = {8, 117, 121, 125, 130, 134};
            packet(fd, 1, hevc, sizes[iteration - 30]);
        } else if (iteration >= 36) {
            if (iteration < 45) packet(fd, 0, picture, iteration - 36);
            else if (iteration == 45) { unsigned char empty_nal[5] = {0}; packet(fd, 0, empty_nal, 5); }
            else if (iteration == 46) { picture[3] = 127; packet(fd, 0, picture, 6); }
            else {
                unsigned char header[128] = {0}; header[0] = 10;
                if (iteration == 49) { header[0] = 0; header[3] = 3; } // 48 MiB: reject before allocating
                send_all(fd, header, iteration == 48 ? 3 : sizeof(header));
                if (iteration == 47) send_all(fd, picture, 2); // EOF in the middle of a payload
            }
        } else {
        packet(fd, 1, config, sizeof(config));
        if (iteration < 8) packet(fd, 1, config, iteration);
        else if (iteration < 16) { config[6] = 0xff; packet(fd, 1, config, sizeof(config)); }
        else if (iteration < 24) { config[11] = 0xff; packet(fd, 1, config, sizeof(config)); }
        packet(fd, 0, picture, 6);
        }
        shutdown(fd, SHUT_WR); // EOF must end the worker, not leak the socket and keep it alive.
        wait_for(&exited);
        pthread_t stopper;
        assert(pthread_create(&stopper, NULL, stop_worker, mirror) == 0);
        usleep(20000);
        assert(!atomic_load(&stopped)); // running=false is NOT permission to free callback state.
        atomic_store(&allow_exit, 1);
        pthread_join(stopper, NULL);
        assert(atomic_load(&stopped));
        if (iteration >= 24 && iteration < 30) assert(atomic_load(&videos) == 1);
        assert(mirror->joined && mirror->mirror_data_sock == -1);
        close(fd);
        raop_rtp_mirror_destroy(mirror); logger_destroy(log);
    }
    puts("50 mirror parser/EOF/join regression cases passed");
    return 0;
}
