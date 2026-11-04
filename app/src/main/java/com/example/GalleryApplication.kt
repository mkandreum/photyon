package com.example

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.ImageDecoderDecoder
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.example.data.GalleryDatabase
import com.example.data.GalleryRepository
import com.example.data.Thumbnails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

class GalleryApplication : Application(), ImageLoaderFactory {
    val repository by lazy { GalleryRepository(this, GalleryDatabase.getDatabase(this)) }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.35).build() }
            // Sources are local: our thumbnails live on disk already and originals need no second copy.
            .diskCachePolicy(CachePolicy.DISABLED)
            .networkCachePolicy(CachePolicy.DISABLED)
            .fetcherDispatcher(Dispatchers.IO.limitedParallelism(6))
            .components {
                add(Thumbnails.ThumbFetcher.Factory())
                add(Thumbnails.ThumbKeyer())
                add(ImageDecoderDecoder.Factory())
            }
            .build()
}
