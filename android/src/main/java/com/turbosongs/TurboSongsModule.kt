package com.turbosongs

import android.content.ContentResolver
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import com.facebook.react.bridge.WritableNativeArray
import com.facebook.react.bridge.WritableNativeMap
import java.io.ByteArrayOutputStream
import java.io.IOException

class TurboSongsModule internal constructor(context: ReactApplicationContext) :
  TurboSongsSpec(context) {

  override fun getName(): String {
    return NAME
  }

  private fun hasPermissions(): Boolean {
    return ContextCompat.checkSelfPermission(reactApplicationContext.applicationContext, android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
  }

  // Example method
  // See https://reactnative.dev/docs/native-modules-android
  @ReactMethod
  override fun getAll(options: ReadableMap, promise: Promise) {
    if(!hasPermissions()){
      promise.reject("Permissions denied","Permissions denied")
      return
    }

    val projection = arrayOf<String>(
      MediaStore.Audio.AudioColumns.TITLE,
      MediaStore.Audio.AudioColumns.ALBUM,
      MediaStore.Audio.AudioColumns.ARTIST,
      MediaStore.Audio.AudioColumns.DURATION,
      MediaStore.Audio.AudioColumns.GENRE,
      MediaStore.Audio.Media.DATA
    )

    val limit =  when (options.hasKey("limit")) {
      true -> options.getInt("limit")
      else -> 20
    }

    val offset =  when (options.hasKey("offset")) {
      true -> options.getInt("offset")
      else -> 0
    }

    val minSongDuration =  when (options.hasKey("minSongDuration")) {
      true -> options.getInt("minSongDuration")
      else -> 1000
    }

    // Bellow android 0
    val selection = MediaStore.Audio.Media.IS_MUSIC + " != 0" + " AND " + MediaStore.Audio.Media.DURATION + " >= " + minSongDuration
    // Android 0 afterwards
    val bundleSelection = bundleOf(
      ContentResolver.QUERY_ARG_SQL_SELECTION bundleTo selection,
      ContentResolver.QUERY_ARG_LIMIT bundleTo limit,
      ContentResolver.QUERY_ARG_OFFSET bundleTo offset,
    )

    val resultSet = parseCursor(projection,
      "$selection LIMIT $limit OFFSET $offset", bundleSelection, null
    )

    promise.resolve(createSongCursor(resultSet, options))
  }

  @ReactMethod
  override fun getAlbums(options: ReadableMap, promise : Promise) {

    if(!hasPermissions()){
      promise.reject("Permissions denied","Permissions denied")
      return
    }

    if(!options.hasKey("artist")){
      promise.reject("Artist name must not be empty", "Artist name must not be empty")
      return
    }

    var projection = arrayOf<String>(
      MediaStore.Audio.Albums.ALBUM_ID,
      MediaStore.Audio.Albums.ALBUM,
      MediaStore.Audio.Albums.ARTIST,
      MediaStore.Audio.AudioColumns.NUM_TRACKS,
      MediaStore.Audio.Media.DATA
    )

    val artist = options?.getString("artist")

    val limit =  when (options.hasKey("limit")) {
      true -> options.getInt("limit")
      else -> 20
    }

    val offset =  when (options.hasKey("offset")) {
      true -> options.getInt("offset")
      else -> 0
    }

    // Bellow android 0
    val selection = MediaStore.Audio.Media.IS_MUSIC + " != 0" + " AND " + MediaStore.Audio.Albums.ARTIST + " LIKE ?"
    val whereArgs = arrayOf("%$artist%")
    // Android 0 afterwards
    val bundleSelection = bundleOf(
      ContentResolver.QUERY_ARG_SQL_SELECTION bundleTo "$selection",
      ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS bundleTo whereArgs,
      ContentResolver.QUERY_ARG_LIMIT bundleTo limit,
      ContentResolver.QUERY_ARG_OFFSET bundleTo offset,
    )

    val resultSet = parseCursor(projection,
      "$selection LIMIT $limit OFFSET $offset", bundleSelection, whereArgs
    )

    promise.resolve(createAlbumCursor(resultSet, options))
  }

  @ReactMethod
  override fun search(options: ReadableMap, promise : Promise) {

    if(!hasPermissions()){
      promise.reject("Permissions denied","Permissions denied")
      return
    }

    if(!options.hasKey("searchBy")){
      promise.reject("Search param must not be empty", "Search param must not be empty")
      return
    }

    var projection = arrayOf<String>(
      MediaStore.Audio.AudioColumns._ID,
      MediaStore.Audio.AudioColumns.TITLE,
      MediaStore.Audio.AudioColumns.ALBUM,
      MediaStore.Audio.AudioColumns.ARTIST,
      MediaStore.Audio.AudioColumns.DURATION,
      MediaStore.Audio.AudioColumns.SIZE,
      MediaStore.Audio.AudioColumns.GENRE,
      MediaStore.Audio.Media.DATA
    )

    val searchBy = options.getString("searchBy")

    val limit =  when (options.hasKey("limit")) {
      true -> options.getInt("limit")
      else -> 20
    }

    val offset =  when (options.hasKey("offset")) {
      true -> options.getInt("offset")
      else -> 0
    }

    val query = "AND ("+ MediaStore.Audio.Albums.ARTIST + " LIKE ? OR " + MediaStore.Audio.Albums.ALBUM + " LIKE ? OR " + MediaStore.Audio.Media.TITLE + " LIKE ?)"

    val whereArgs = arrayOf("%$searchBy%","%$searchBy%","%$searchBy%")

    // Bellow android 0
    val selection = MediaStore.Audio.Media.IS_MUSIC + " != 0"
    // Android 0 afterwards
    val bundleSelection = bundleOf(
      ContentResolver.QUERY_ARG_SQL_SELECTION bundleTo "$selection $query",
      ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS bundleTo whereArgs,
      ContentResolver.QUERY_ARG_LIMIT bundleTo limit,
      ContentResolver.QUERY_ARG_OFFSET bundleTo offset,
    )

    val resultSet = parseCursor(projection,
      "$selection $query LIMIT $limit OFFSET $offset", bundleSelection, whereArgs
    )

    promise.resolve(createSongCursor(resultSet, options))
  }

  private fun parseCursor(projection: Array<String>, selection: String, bundleSelection: Bundle, searchParams: Array<String>?): Cursor? {
    val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val resultSet : Cursor? = when {
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
        reactApplicationContext.contentResolver.query(uri, projection, bundleSelection, null)
      } else -> {
        reactApplicationContext.contentResolver.query(uri, projection, selection, searchParams, null)
      }
    }
    return resultSet
  }

  private fun createAlbumCursor(resultSet: Cursor?, options: ReadableMap?): WritableArray {
    val items: WritableArray = WritableNativeArray()
    var coverQuality = 100
    if (resultSet != null) {
      if(resultSet.moveToFirst()) {
        if(options != null && options.hasKey("coverQuality")){
          coverQuality = options.getInt("coverQuality")
        }

        do {
          val id = resultSet.getString(0)
          val album = resultSet.getString(1)
          val artist = resultSet.getString(2)
          val numberOfSongs = resultSet.getString(3)
          val path = resultSet.getString(4)

          var song: WritableMap = WritableNativeMap();
          song.putString("id", id)
          song.putString("album", album)
          song.putString("artist", artist)
          song.putString("numberOfSongs", numberOfSongs)
          song.putString("cover", getCover(path, coverQuality))

          items.pushMap(song)
        } while (resultSet.moveToNext())
      }
    }

    resultSet?.close()
    return items
  }
  private fun createSongCursor(resultSet: Cursor?, options: ReadableMap?) : WritableArray {
    val items: WritableArray = WritableNativeArray()
    var coverQuality = 100
    if (resultSet != null) {
      println(resultSet.moveToFirst())

      if(resultSet.moveToFirst()) {

        if(options != null && options.hasKey("coverQuality")){
          coverQuality = options.getInt("coverQuality")
        }

        do {
          val title = resultSet.getString(0)
          val album = resultSet.getString(1)
          val artist = resultSet.getString(2)
          val duration = resultSet.getString(3)
          val genre = resultSet.getString(4)
          val path = resultSet.getString(5)

          var song: WritableMap = WritableNativeMap();
          song.putString("url", path)
          song.putString("title", title)
          song.putString("album", album)
          song.putString("artist", artist)
          song.putInt("duration", Integer.parseInt(duration))
          song.putString("genre", genre)
          song.putString("cover", getCover(path, coverQuality))

          items.pushMap(song)
        } while (resultSet.moveToNext())
      }
    }



    resultSet?.close()
    return items
  }


  private fun getCover(path: String?, quality: Int) : String {
    if(path == null) return ""

    val mmr = MediaMetadataRetriever()
    mmr.setDataSource(path)

    return try {
      val cover = mmr.embeddedPicture ?: return ""

      var byteArrayOutputStream = ByteArrayOutputStream()
      var bitmap = BitmapFactory.decodeByteArray(cover, 0, cover.size)
      bitmap.compress(Bitmap.CompressFormat.JPEG, quality, byteArrayOutputStream)
      "data:image/jpeg;base64," + Base64.encodeToString(byteArrayOutputStream.toByteArray(), Base64.DEFAULT)
    } catch (e: IOException){
      ""
    }
  }

  companion object {
    const val NAME = "TurboSongs"
  }
}

