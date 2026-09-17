/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.wallpaper.picker.category.domain.interactor.implementations

import android.app.WallpaperInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.util.Log
import com.android.wallpaper.model.CreativeCategory
import com.android.wallpaper.model.WallpaperInfoContract
import com.android.wallpaper.picker.category.domain.interactor.CreativeCategoryInteractor
import com.android.wallpaper.picker.data.category.CategoryModel
import com.android.wallpaper.picker.di.modules.BackgroundDispatcher
import com.android.wallpaper.util.converter.WallpaperModelFactory
import com.android.wallpaper.util.converter.category.CategoryFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** This class implements the business logic in assembling creative category models */
@Singleton
class CreativeCategoryInteractorImpl
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val categoryFactory: CategoryFactory,
    private val wallpaperModelFactory: WallpaperModelFactory,
    @BackgroundDispatcher private val backgroundScope: CoroutineScope,
) : CreativeCategoryInteractor {
    private val _categories = MutableStateFlow<List<CategoryModel>>(emptyList())
    override val categories: Flow<List<CategoryModel>> = _categories.asStateFlow()

    private val _standaloneCategories = MutableStateFlow<List<CategoryModel>>(emptyList())
    override val standaloneCategories: Flow<List<CategoryModel>> =
        _standaloneCategories.asStateFlow()

    init {
        updateCreativeCategories()
    }

    override fun updateCreativeCategories() {
        backgroundScope.launch {
            val models = getCreativeCategories().map { category ->
                category to toCategoryModel(category)
            }
            _standaloneCategories.value = models.filter {
                it.first.mWallpaperInfo.packageName == MAGIC_PORTRAIT_PACKAGE
            }.map { it.second }
            _categories.value = models.filter {
                it.first.mWallpaperInfo.packageName != MAGIC_PORTRAIT_PACKAGE
            }.map { it.second }
        }
    }

    override fun updatePackThemeCategory() {}

    private fun getCreativeCategories(): List<CreativeCategory> {
        val packageManager = context.packageManager
        val services = packageManager.queryIntentServices(
            Intent(CREATIVE_WALLPAPER_ACTION), PackageManager.GET_META_DATA)
        return services.mapNotNull { resolveInfo ->
            try {
                val wallpaperInfo = WallpaperInfo(context, resolveInfo)
                readCategories(wallpaperInfo)
            } catch (e: Exception) {
                Log.w(TAG, "Skipping creative wallpaper ${resolveInfo.serviceInfo.name}", e)
                null
            }
        }.flatten()
    }

    private fun readCategories(wallpaperInfo: WallpaperInfo): List<CreativeCategory> {
        val metadata = wallpaperInfo.serviceInfo.metaData
        val categoryUri = metadata?.getString(CreativeCategory.KEY_WALLPAPER_CREATIVE_CATEGORY)
            ?.let(Uri::parse)
        if (categoryUri?.authority == null) {
            return listOf(
                CreativeCategory(
                    context,
                    wallpaperInfo.loadLabel(context.packageManager).toString(),
                    wallpaperInfo.packageName,
                    null,
                    0,
                    wallpaperInfo,
                )
            )
        }

        val categories = mutableListOf<CreativeCategory>()
        context.contentResolver.acquireUnstableContentProviderClient(categoryUri.authority!!)
            ?.use { client ->
                client.query(categoryUri, null, null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        parseCategory(wallpaperInfo, cursor)?.let(categories::add)
                    }
                }
            }
        return categories
    }

    private fun parseCategory(wallpaperInfo: WallpaperInfo, cursor: Cursor): CreativeCategory? {
        val id = cursor.string(WallpaperInfoContract.CATEGORY_ID) ?: return null
        val title = cursor.string(WallpaperInfoContract.CATEGORY_TITLE) ?: return null
        val thumbnail = cursor.string(WallpaperInfoContract.CATEGORY_THUMBNAIL)
            ?.takeIf(String::isNotEmpty)
            ?.let(Uri::parse)
        val priority = cursor.string(WallpaperInfoContract.CATEGORY_PRIORITY)?.toIntOrNull() ?: 0
        val isCollectionWallpaper = cursor.getColumnIndex(
            WallpaperInfoContract.CATEGORY_IS_COLLECTION_WALLPAPER).takeIf { it >= 0 }
            ?.let { cursor.getInt(it) > 0 } ?: false
        return CreativeCategory(
            context, title, id, thumbnail, priority, wallpaperInfo, isCollectionWallpaper
        )
    }

    private fun toCategoryModel(category: CreativeCategory): CategoryModel {
        val categoryModel = categoryFactory.getCategoryModel(category)
        return categoryModel.copy(
            commonCategoryData = categoryModel.commonCategoryData.copy(
                fetchWallpapers = { collectionId ->
                    CreativeCategory.readCreativeWallpapers(
                        context, collectionId, category.mWallpaperInfo
                    )?.map { wallpaperModelFactory.getWallpaperModel(context, it) }
                }
            )
        )
    }

    private fun Cursor.string(column: String): String? {
        val index = getColumnIndex(column)
        return index.takeIf { it >= 0 && !isNull(it) }?.let(::getString)
    }

    companion object {
        private const val TAG = "CreativeCategoryInteractor"
        private const val CREATIVE_WALLPAPER_ACTION =
            "com.google.android.apps.wallpaper.action.WALLPAPER_CREATION"
        private const val MAGIC_PORTRAIT_PACKAGE = "com.google.android.apps.magicportrait"
    }
}
