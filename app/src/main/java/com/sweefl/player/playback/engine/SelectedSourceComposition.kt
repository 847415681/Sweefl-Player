package com.sweefl.player.playback.engine

/** Preserve the caller item (including subtitles/metadata); create audio independently. */
internal fun <Item, Source> composeSelectedSources(
    caller: Item,
    matchesSelected: (Item) -> Boolean,
    createPrimary: (Item) -> Source,
    createAudioItem: (() -> Item)?,
    createAudio: (Item) -> Source,
    merge: (Source, Source) -> Source,
): Source {
    val primary = createPrimary(caller)
    return if (createAudioItem != null && matchesSelected(caller)) {
        merge(primary, createAudio(createAudioItem()))
    } else primary
}
