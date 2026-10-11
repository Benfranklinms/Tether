package dev.benfranklin.tether.core.proto

import kotlinx.serialization.Serializable

/**
 * Protocol revision 8 (app 0.5.4): Shelf folders. No new message types: SHELF_SYNC gains
 * `folders` / `removedFolders` and each ShelfItem an optional `folderId`. Same rules as
 * revisions 2-7: Hello.v stays 1, the cap is announced in Hello.caps, and a side only sends
 * the new fields to a peer that announced [SHELF_FOLDERS] (older peers would ignore them
 * anyway and see every item as loose). Offline-copy settings and "Keep offline" pins are
 * local to each device and never travel.
 */
object Caps8 {
    /** SHELF_SYNC.folders / removedFolders and ShelfItem.folderId. */
    const val SHELF_FOLDERS = "shelffolders"
    val ALL: List<String> = listOf(SHELF_FOLDERS)
}

/**
 * One Shelf folder. [order] is a fractional index (lower = further left in the chip row);
 * [updated] (ms) decides merges exactly like ShelfItem.updated.
 */
@Serializable data class ShelfFolder(val id: String, val name: String, val order: Double, val updated: Long)
