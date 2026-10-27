/* Exercises the actual applied UxPlay RTP queue, including patch 0012.
 * The clock is deterministic. Crypto/logging and two wire integer readers are
 * stubbed; all test payloads are shorter than an AES block, so encryption and
 * codec behavior are deliberately outside this test's scope. */
#include <time.h>
#include <stdint.h>
#include <assert.h>

static uint64_t test_now_ns = 1000000000ULL;
static int test_clock_gettime(clockid_t clock, struct timespec *now) {
    assert(clock == CLOCK_MONOTONIC);
    now->tv_sec = (time_t) (test_now_ns / 1000000000ULL);
    now->tv_nsec = (long) (test_now_ns % 1000000000ULL);
    return 0;
}
#define clock_gettime test_clock_gettime
#include "../../app/src/main/cpp/third_party/UxPlay/lib/raop_buffer.c"
#undef clock_gettime

struct aes_ctx_s { int unused; };
static struct aes_ctx_s crypto_stub;
static unsigned int decrypt_calls;

aes_ctx_t *aes_cbc_init(const uint8_t *key, const uint8_t *iv, aes_direction_t direction) {
    (void) key; (void) iv; (void) direction;
    return &crypto_stub;
}
void aes_cbc_destroy(aes_ctx_t *ctx) { assert(ctx == &crypto_stub); }
void aes_cbc_reset(aes_ctx_t *ctx) { assert(ctx == &crypto_stub); }
void aes_cbc_decrypt(aes_ctx_t *ctx, const uint8_t *in, uint8_t *out, int len) {
    assert(ctx == &crypto_stub && len == 0);
    (void) in; (void) out;
    decrypt_calls++;
}
void logger_log(logger_t *logger, int level, const char *fmt, ...) {
    (void) logger; (void) level; (void) fmt;
}
char *utils_data_to_string(const unsigned char *data, int len, int columns) {
    (void) data; (void) len; (void) columns;
    assert(!"decryption debug logging must be disabled for this test");
    return NULL;
}
uint16_t byteutils_get_short_be(unsigned char *data, int offset) {
    return ((uint16_t) data[offset] << 8) | data[offset + 1];
}
uint32_t byteutils_get_int_be(unsigned char *data, int offset) {
    return ((uint32_t) data[offset] << 24) | ((uint32_t) data[offset + 1] << 16) |
           ((uint32_t) data[offset + 2] << 8) | data[offset + 3];
}

static raop_buffer_t *new_buffer(unsigned int timeout_ms) {
    unsigned char key[16] = {0};
    raop_buffer_t *buffer = raop_buffer_init(NULL, key, key);
    assert(buffer);
    raop_buffer_set_resend_timeout(buffer, timeout_ms);
    test_now_ns = 1000000000ULL;
    return buffer;
}

enum packet_kind { AUDIO, HEADER_ONLY, EMPTY_MARKER };
static int enqueue(raop_buffer_t *buffer, uint16_t seq, enum packet_kind kind) {
    unsigned char packet[16] = {0};
    packet[2] = (unsigned char) (seq >> 8);
    packet[3] = (unsigned char) seq;
    packet[4] = 0x12; packet[5] = 0x34; packet[6] = 0x56; packet[7] = 0x78;
    unsigned short length = 12;
    if (kind == AUDIO) { packet[12] = 0x81; length = 13; }
    if (kind == EMPTY_MARKER) {
        packet[12] = 0x00; packet[13] = 0x68; packet[14] = 0x34; packet[15] = 0x00;
        length = 16;
    }
    return raop_buffer_enqueue(buffer, packet, length, 1);
}
static void expect_audio(raop_buffer_t *buffer, uint16_t expected_seq, int no_resend) {
    unsigned int length = 0;
    uint32_t timestamp = 0;
    unsigned short seq = 0;
    unsigned char *payload = raop_buffer_dequeue(buffer, &length, &timestamp, &seq, no_resend);
    assert(payload && length == 1 && payload[0] == 0x81);
    assert(seq == expected_seq && timestamp == 0x12345678);
    free(payload);
}
static void expect_wait(raop_buffer_t *buffer, int no_resend) {
    unsigned int length = 0;
    uint32_t timestamp = 0;
    unsigned short seq = 0;
    assert(raop_buffer_dequeue(buffer, &length, &timestamp, &seq, no_resend) == NULL);
}

static void sequence_wrap_and_late_duplicates(void) {
    raop_buffer_t *b = new_buffer(150);
    assert(enqueue(b, 65534, AUDIO) == 1);
    assert(enqueue(b, 65535, EMPTY_MARKER) == 1);
    assert(enqueue(b, 0, AUDIO) == 1);
    assert(enqueue(b, 1, AUDIO) == 1);
    assert(enqueue(b, 0, AUDIO) == 0); // duplicate while still queued
    expect_audio(b, 65534, 0);
    expect_audio(b, 0, 0);
    expect_audio(b, 1, 0);
    expect_wait(b, 0);
    assert(enqueue(b, 65535, AUDIO) == 0); // late across the wrap after drain
    assert(enqueue(b, 1, AUDIO) == 0);
    assert(enqueue(b, 2, AUDIO) == 1);
    expect_audio(b, 2, 0);
    raop_buffer_destroy(b);
}
static void empty_packets_advance_without_decoding(void) {
    raop_buffer_t *b = new_buffer(150);
    unsigned int before = decrypt_calls;
    assert(enqueue(b, 20, HEADER_ONLY) == 1);
    assert(enqueue(b, 21, EMPTY_MARKER) == 1);
    assert(decrypt_calls == before);
    assert(enqueue(b, 22, AUDIO) == 1);
    assert(decrypt_calls == before + 1);
    expect_audio(b, 22, 0); // both markers consumed in this same dequeue
    expect_wait(b, 0);
    raop_buffer_destroy(b);
}
static void flush_preserves_sequence_fence(void) {
    raop_buffer_t *b = new_buffer(150);
    assert(enqueue(b, 99, AUDIO) == 1);
    raop_buffer_flush(b, 101);
    assert(enqueue(b, 100, AUDIO) == 0);
    assert(enqueue(b, 101, AUDIO) == 1);
    expect_audio(b, 101, 0);
    assert(enqueue(b, 101, AUDIO) == 0);
    assert(enqueue(b, 102, AUDIO) == 1);
    expect_audio(b, 102, 0);
    raop_buffer_flush(b, -1); // only an unspecified boundary restarts numbering
    assert(enqueue(b, 1, AUDIO) == 1);
    expect_audio(b, 1, 0);
    raop_buffer_destroy(b);
}
static void missing_packets_share_one_bounded_wait(void) {
    raop_buffer_t *b = new_buffer(150);
    assert(enqueue(b, 100, AUDIO) == 1);
    expect_audio(b, 100, 0);
    assert(enqueue(b, 105, AUDIO) == 1); // 101..104 are missing
    expect_wait(b, 0);
    test_now_ns += 149999999ULL;
    assert(enqueue(b, 106, AUDIO) == 1); // newer arrivals must not extend the wait
    expect_wait(b, 0);
    test_now_ns += 1;
    expect_audio(b, 105, 0); // all four holes skipped at exactly 150 ms
    expect_audio(b, 106, 0);
    assert(enqueue(b, 103, AUDIO) == 0);
    raop_buffer_destroy(b);
}
static void no_resend_skips_without_waiting(void) {
    raop_buffer_t *b = new_buffer(150);
    raop_buffer_flush(b, 10);
    assert(enqueue(b, 13, AUDIO) == 1);
    expect_audio(b, 13, 1);
    raop_buffer_destroy(b);
}
struct resend_request { unsigned int calls; unsigned short seq, count; };
static int capture_resend(void *opaque, unsigned short seq, unsigned short count) {
    struct resend_request *request = opaque;
    request->calls++;
    request->seq = seq;
    request->count = count;
    return 0;
}
static void alac_keeps_original_retransmission_policy(void) {
    raop_buffer_t *b = new_buffer(0);
    assert(enqueue(b, 30, AUDIO) == 1);
    expect_audio(b, 30, 0);
    assert(enqueue(b, 33, AUDIO) == 1);
    test_now_ns += 10000000000ULL;
    expect_wait(b, 0); // AAC timeout must not be enabled for ALAC
    struct resend_request request = {0};
    raop_buffer_handle_resends(b, capture_resend, &request);
    assert(request.calls == 1 && request.seq == 31 && request.count == 2);
    assert(enqueue(b, 31, AUDIO) == 1);
    assert(enqueue(b, 32, AUDIO) == 1);
    expect_audio(b, 31, 0);
    expect_audio(b, 32, 0);
    expect_audio(b, 33, 0);
    raop_buffer_destroy(b);
}

int main(void) {
    sequence_wrap_and_late_duplicates();
    empty_packets_advance_without_decoding();
    flush_preserves_sequence_fence();
    missing_packets_share_one_bounded_wait();
    no_resend_skips_without_waiting();
    alac_keeps_original_retransmission_policy();
    puts("6 applied RTP queue regression tests passed (crypto and codec excluded)");
    return 0;
}
