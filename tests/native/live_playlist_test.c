/* Tests the applied UxPlay patch, with no server or network. Link with dead-strip
 * to omit unrelated third-party entry points from this translation unit. */
#include <inttypes.h>
#include "../../app/src/main/cpp/third_party/UxPlay/lib/airplay_video.c"

static airplay_video_t *make_video(void) {
    airplay_video_t *v = calloc(1, sizeof(*v));
    pthread_mutex_init(&v->media_data_store_lock, NULL);
    v->num_uri = 1;
    v->media_data_store = calloc(1, sizeof(media_item_t));
    v->media_data_store[0].uri = strdup("https://example.test/live.m3u8");
    return v;
}

static int store(airplay_video_t *v, uint64_t sequence) {
    char playlist[256];
    snprintf(playlist, sizeof(playlist),
             "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:%" PRIu64 "\n#EXTINF:5,\nsegment.ts\n", sequence);
    char *copy = strdup(playlist);
    int count = 1;
    float duration = 5;
    bool endlist = false;
    int result = store_media_playlist(v, copy, &count, &duration, &endlist, 0);
    if (result < 0) free(copy);
    return result;
}

static bool poll(airplay_video_t *v) {
    bool should_fetch = false;
    const char *uri = NULL;
    int count = 0;
    float duration = 0;
    char *playlist = get_media_playlist(v, &count, &duration, "live.m3u8", &should_fetch, &uri);
    free(playlist);
    if (should_fetch) assert(uri && strcmp(uri, "https://example.test/live.m3u8") == 0);
    return should_fetch;
}

static void full_width_sequence_does_not_wrap(void) {
    airplay_video_t *v = make_video();
    media_item_t *item = &v->media_data_store[0];
    assert(store(v, INT32_MAX) == 0);
    item->fetch_pending = true;
    assert(store(v, (uint64_t) INT32_MAX + 1) == 0);
    assert(item->media_sequence == (uint64_t) INT32_MAX + 1);
    item->fetch_pending = true;
    assert(store(v, (uint64_t) UINT32_MAX + 1) == 0);
    assert(item->media_sequence == (uint64_t) UINT32_MAX + 1);
    item->fetch_pending = true;
    assert(store(v, UINT64_MAX - 1) == 0);
    item->fetch_pending = true;
    assert(store(v, UINT64_MAX) == 0);
    assert(item->media_sequence == UINT64_MAX);
    item->fetch_pending = true;
    assert(store(v, UINT64_MAX - 1) == -3);
    assert(item->media_sequence == UINT64_MAX);
    airplay_video_destroy(v);
}

int main(void) {
    airplay_video_t *v = make_video();
    assert(store(v, 10) == 0);
    media_item_t *item = &v->media_data_store[0];
    item->last_fetched_ns = playlist_monotonic_time_ns() - MEDIA_PLAYLIST_REFRESH_NS;
    assert(poll(v));
    assert(!poll(v)); // simultaneous/subsequent polls retain a single in-flight request
    item->fetch_started_ns = playlist_monotonic_time_ns() - MEDIA_PLAYLIST_FETCH_TIMEOUT_NS;
    assert(poll(v)); // a lost response does not lock refresh forever
    assert(store(v, 12) == 0);
    item->fetch_pending = true; // a delayed older response arrives after the retry
    assert(store(v, 11) == -3);
    assert(item->media_sequence == 12);
    item->fetch_pending = false;
    item->endlist = true;
    item->last_fetched_ns = 0;
    assert(!poll(v)); // completed VOD never enters the live-refresh loop
    airplay_video_destroy(v);
    full_width_sequence_does_not_wrap();
    puts("6 live playlist regression checks passed");
    return 0;
}
