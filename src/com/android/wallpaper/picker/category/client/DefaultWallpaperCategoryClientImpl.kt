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

package com.android.wallpaper.picker.category.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import androidx.annotation.XmlRes
import com.android.wallpaper.R
import com.android.wallpaper.model.Category
import com.android.wallpaper.model.DefaultWallpaperInfo
import com.android.wallpaper.model.ImageCategory
import com.android.wallpaper.model.LegacyPartnerWallpaperInfo
import com.android.wallpaper.model.LiveWallpaperInfo
import com.android.wallpaper.model.ThirdPartyAppCategory
import com.android.wallpaper.model.ThirdPartyLiveWallpaperCategory
import com.android.wallpaper.model.WallpaperCategory
import com.android.wallpaper.model.WallpaperInfo
import com.android.wallpaper.module.PartnerProvider
import com.android.wallpaper.util.WallpaperParser
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * This class is responsible for fetching wallpaper categories, listed as follows:
 * 1. MyPhotos category that allows users to select custom photos
 * 2. OnDevice category that are pre-loaded wallpapers on device (legacy way of pre-loading
 *    wallpapers, modern way is described below)
 * 3. System categories on device (modern way of pre-loading wallpapers on device)
 * 4. Third party app categories
 */
@Singleton
class DefaultWallpaperCategoryClientImpl
@Inject
constructor(
    @ApplicationContext val context: Context,
    private val partnerProvider: PartnerProvider,
    private val wallpaperXMLParser: WallpaperParser,
    private val liveWallpapersClient: LiveWallpapersClient
) : DefaultWallpaperCategoryClient {

    private var systemCategories: List<Category>? = null

    override fun resetResources() {
        systemCategories = null
        partnerProvider.refreshResourcesDueToLocaleChange()
    }

    /** This method is used for fetching and creating the MyPhotos category tile. */
    override suspend fun getMyPhotosCategory(): Category {
        val imageCategory = ImageCategory(
                    context.getString(R.string.my_photos_category_title),
                    context.getString(R.string.image_wallpaper_collection_id),
                    PRIORITY_MY_PHOTOS_WHEN_CREATIVE_WALLPAPERS_ENABLED,
                    R.drawable.wallpaperpicker_emptystate, /* overlayIconResId */
            )
        return imageCategory
    }

    /**
     * This method is used for fetching the on-device categories. This returns a category which
     * incorporates both GEL and bundled wallpapers.
     */
    override suspend fun getOnDeviceCategory(): Category? {
        val onDeviceWallpapers = mutableListOf<WallpaperInfo>()

        if (!partnerProvider.shouldHideDefaultWallpaper()) {
            val defaultWallpaperInfo = DefaultWallpaperInfo()
            onDeviceWallpapers.add(defaultWallpaperInfo)
        }

        val partnerWallpaperInfos = wallpaperXMLParser.parsePartnerWallpaperInfoResources()
        onDeviceWallpapers.addAll(partnerWallpaperInfos)

        val legacyPartnerWallpaperInfos = LegacyPartnerWallpaperInfo.getAll(context)
        onDeviceWallpapers.addAll(legacyPartnerWallpaperInfos)

        val privateWallpapers = getPrivateDeviceWallpapers()
        privateWallpapers?.let { onDeviceWallpapers.addAll(it) }

        return onDeviceWallpapers
            .takeIf { it.isNotEmpty() }
            ?.let {
                val wallpaperCategory =
                    WallpaperCategory(
                        context.getString(R.string.on_device_wallpapers_category_title),
                        context.getString(R.string.on_device_wallpaper_collection_id),
                        it,
                        PRIORITY_ON_DEVICE
                    )
                wallpaperCategory
            }
    }

    override suspend fun getThirdPartyCategory
                (excludedPackageNames: List<String>): List<Category> {
        val pickWallpaperIntent = Intent(Intent.ACTION_SET_WALLPAPER)
        val apps = context.packageManager.queryIntentActivities(pickWallpaperIntent, 0)

        // Get list of image picker intents.
        val pickImageIntent = Intent(Intent.ACTION_GET_CONTENT)
        pickImageIntent.setType("image/*")
        val imagePickerActivities = context.packageManager.queryIntentActivities(pickImageIntent, 0)

        val thirdPartyApps = apps.mapNotNull { info ->
            val itemComponentName = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
            val itemPackageName = itemComponentName.packageName

            if (excludedPackageNames.contains(itemPackageName) ||
                    itemPackageName == context.packageName ||
                    imagePickerActivities.any { it.activityInfo.packageName == itemPackageName }) {
                null
            } else {
                ThirdPartyAppCategory(
                        context,
                        info, context.getString(R.string.third_party_app_wallpaper_collection_id) + "_" + itemPackageName,
                        PRIORITY_THIRD_PARTY
                )
            }
        }

        return thirdPartyApps
    }

    override suspend fun getThirdPartyLiveWallpaperCategory
                (excludedPackageNames: Set<String>): List<Category> {
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_LIVE_WALLPAPER)) {
            val pixelCategories = getPixelLiveWallpaperCategories()
            val excludedPackages = excludedPackageNames + PIXEL_LIVE_WALLPAPER_PACKAGE +
                getCreativeWallpaperPackageNames()
            val liveWallpapers = liveWallpapersClient.getAll(excludedPackages)
            val categories = pixelCategories.toMutableList()
            if (liveWallpapers.isNotEmpty()) {
                categories += ThirdPartyLiveWallpaperCategory(
                    context.getString(R.string.live_wallpapers_category_title),
                    context.getString(R.string.live_wallpaper_collection_id), liveWallpapers,
                    PRIORITY_LIVE,
                    excludedPackages)
            }
            return categories
        }
        return listOf()
    }

    override fun getExcludedLiveWallpaperPackageNames(): Set<String> {
        val excluded = mutableSetOf<String>()
        systemCategories?.forEach { category ->
            if (category is WallpaperCategory) {
                category.wallpapers?.forEach { wallpaperInfo ->
                    if (wallpaperInfo is LiveWallpaperInfo) {
                        excluded.add(wallpaperInfo.wallpaperComponent.packageName)
                    }
                }
            }
        }
        excluded.addAll(getCreativeWallpaperPackageNames())
        return excluded
    }

    private fun getCreativeWallpaperPackageNames(): Set<String> {
        return context.packageManager.queryIntentServices(
                Intent(CREATIVE_WALLPAPER_ACTION), PackageManager.GET_META_DATA)
            .map { it.serviceInfo.packageName }
            .toSet()
    }

    private fun getPixelLiveWallpaperCategories(): List<Category> {
        val packageManager = context.packageManager
        return try {
            val applicationInfo = packageManager.getApplicationInfo(
                PIXEL_LIVE_WALLPAPER_PACKAGE, PackageManager.GET_META_DATA)
            val resources = packageManager.getResourcesForApplication(applicationInfo)
            val categoryArrayId = resources.getIdentifier(
                "pixel_live_categories", "array", PIXEL_LIVE_WALLPAPER_PACKAGE)
            if (categoryArrayId == 0) return emptyList()

            resources.getStringArray(categoryArrayId).mapIndexedNotNull { index, categoryName ->
                val title = resources.getStringResource(categoryName, "title")
                    ?: return@mapIndexedNotNull null
                val packageName = resources.getStringResource(categoryName, "package_name")
                    ?: return@mapIndexedNotNull null
                val featuredService = resources.getStringResource(
                    categoryName, "featured_service_name")
                val serviceNamesId = resources.getIdentifier(
                    "${categoryName}_service_names", "array", PIXEL_LIVE_WALLPAPER_PACKAGE)
                val serviceNames = if (serviceNamesId == 0) {
                    emptyList()
                } else {
                    resources.getStringArray(serviceNamesId).toList()
                }
                val wallpapers = LiveWallpaperInfo.getFromSpecifiedPackage(
                    context, packageName, serviceNames, true, "pixel_live_category_$categoryName")
                if (wallpapers.isEmpty()) return@mapIndexedNotNull null
                val featuredIndex = wallpapers.indexOfFirst {
                    it.wallpaperComponent?.serviceName == featuredService
                }.takeIf { it >= 0 } ?: 0
                val priority = resources.getIntegerResource(categoryName, "priority") ?: 0
                WallpaperCategory(
                    title,
                    "pixel_live_category_$categoryName",
                    featuredIndex,
                    wallpapers,
                    priority + PIXEL_LIVE_PRIORITY_OFFSET,
                    true,
                    PIXEL_LIVE_DOWNLOAD_ACTION,
                )
            }
        } catch (e: PackageManager.NameNotFoundException) {
            emptyList()
        } catch (e: Resources.NotFoundException) {
            emptyList()
        }
    }

    private fun Resources.getStringResource(categoryName: String, suffix: String): String? {
        val id = getIdentifier(
            "${categoryName}_$suffix", "string", PIXEL_LIVE_WALLPAPER_PACKAGE)
        return id.takeIf { it != 0 }?.let { getString(it) }
    }

    private fun Resources.getIntegerResource(categoryName: String, suffix: String): Int? {
        val id = getIdentifier(
            "${categoryName}_$suffix", "integer", PIXEL_LIVE_WALLPAPER_PACKAGE)
        return id.takeIf { it != 0 }?.let { getInteger(it) }
    }

    override fun getExcludedThirdPartyPackageNames(): List<String> {
        return listOf(
                LAUNCHER_PACKAGE,  // Legacy launcher
                LIVE_WALLPAPER_PICKER) // Live wallpaper picker
    }

    /** This method is used for fetching the system categories. */
    override suspend fun getSystemCategories(): List<Category> {
        systemCategories?.let { return it }

        val partnerRes = partnerProvider.resources
        val packageName = partnerProvider.packageName
        if (partnerRes == null || packageName == null) {
            return listOf()
        }

        @XmlRes val wallpapersResId =
            partnerRes.getIdentifier(PartnerProvider.WALLPAPER_RES_ID, "xml", packageName)
        // Certain partner configurations don't have wallpapers provided, so need to check;
        // return early if they are missing.
        if (wallpapersResId == 0) {
            return listOf()
        }

        systemCategories =
            wallpaperXMLParser.parseSystemCategories(partnerRes.getXml(wallpapersResId))
        return systemCategories as List<Category>
    }

    private fun getLocale(): Locale {
        return context.resources.configuration.locales.get(0)
    }

    private fun getPrivateDeviceWallpapers(): Collection<WallpaperInfo>? {
        return null
    }

    companion object {
        private const val TAG = "DefaultWallpaperCategoryClientImpl"
        private const val LAUNCHER_PACKAGE = "com.android.launcher"
        private const val LIVE_WALLPAPER_PICKER = "com.android.wallpaper.livepicker"
        private const val CREATIVE_WALLPAPER_ACTION =
            "com.google.android.apps.wallpaper.action.WALLPAPER_CREATION"
        private const val PIXEL_LIVE_WALLPAPER_PACKAGE = "com.google.pixel.livewallpaper"
        private const val PIXEL_LIVE_PRIORITY_OFFSET = 151
        private const val PIXEL_LIVE_DOWNLOAD_ACTION =
            "com.google.pixel.livewallpaper.action.DOWNLOAD_LIVE_WALLPAPER"

        /**
         * Relative category priorities. Lower numbers correspond to higher priorities (i.e., should
         * appear higher in the categories list).
         */
        private const val PRIORITY_MY_PHOTOS_WHEN_CREATIVE_WALLPAPERS_ENABLED = 51
        private const val PRIORITY_ON_DEVICE = 200
        private const val PRIORITY_LIVE = 300
        private const val PRIORITY_THIRD_PARTY = 400
    }
}
