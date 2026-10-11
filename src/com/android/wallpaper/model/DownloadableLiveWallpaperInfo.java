/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.wallpaper.model;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.service.wallpaper.WallpaperService;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.xmlpull.v1.XmlPullParserException;

/** A live wallpaper whose dynamic feature is available to download from its provider. */
public final class DownloadableLiveWallpaperInfo extends LiveWallpaperInfo {
    public static final Parcelable.Creator<DownloadableLiveWallpaperInfo> CREATOR =
            new Parcelable.Creator<DownloadableLiveWallpaperInfo>() {
                @Override
                public DownloadableLiveWallpaperInfo createFromParcel(Parcel in) {
                    return new DownloadableLiveWallpaperInfo(in);
                }

                @Override
                public DownloadableLiveWallpaperInfo[] newArray(int size) {
                    return new DownloadableLiveWallpaperInfo[size];
                }
            };

    private static final String TAG = "DownloadableLiveWallpaperInfo";
    private static final Uri WALLPAPERS_URI =
            Uri.parse("content://com.google.pixel.livewallpaper.provider/wallpapers");
    private static final String COLUMN_TO_BE_INSTALLED = "to_be_installed_wallpaper";

    public DownloadableLiveWallpaperInfo(android.app.WallpaperInfo info, String collectionId) {
        super(info, false, collectionId);
    }

    private DownloadableLiveWallpaperInfo(Parcel in) {
        super(in);
    }

    /**
     * Finds Pixel wallpaper services which Pixel's provider reports as pending installation.
     * Disabled services are included in the package-manager query, but are only exposed when the
     * provider explicitly marks their component as downloadable.
     */
    public static List<WallpaperInfo> getFromSpecifiedPackage(
            Context context,
            String packageName,
            @Nullable List<String> serviceNames,
            String collectionId) {
        Set<String> pendingComponents = getPendingComponents(context);
        Log.i(TAG, "Pixel provider pending component count=" + pendingComponents.size());
        if (pendingComponents.isEmpty()) {
            return new ArrayList<>();
        }

        Intent intent = new Intent(WallpaperService.SERVICE_INTERFACE);
        List<ResolveInfo> resolveInfos =
                context.getPackageManager()
                        .queryIntentServices(
                                intent,
                                PackageManager.GET_META_DATA
                                        | PackageManager.MATCH_DISABLED_COMPONENTS);
        List<WallpaperInfo> wallpapers = new ArrayList<>();
        int matchingPackageServices = 0;
        int matchingCategoryServices = 0;
        int matchingPendingServices = 0;
        for (ResolveInfo resolveInfo : resolveInfos) {
            if (resolveInfo.serviceInfo == null
                    || !packageName.equals(resolveInfo.serviceInfo.packageName)) {
                continue;
            }
            matchingPackageServices++;
            if (serviceNames != null
                    && !serviceNames.isEmpty()
                    && !serviceNames.contains(resolveInfo.serviceInfo.name)) {
                continue;
            }
            matchingCategoryServices++;
            ComponentName component = new ComponentName(
                    resolveInfo.serviceInfo.packageName, resolveInfo.serviceInfo.name);
            if (!pendingComponents.contains(component.flattenToString())) {
                continue;
            }
            matchingPendingServices++;
            try {
                android.app.WallpaperInfo info = new android.app.WallpaperInfo(context, resolveInfo);
                wallpapers.add(new DownloadableLiveWallpaperInfo(info, collectionId));
            } catch (IOException | XmlPullParserException e) {
                Log.w(TAG, "Skipping downloadable wallpaper " + resolveInfo.serviceInfo, e);
            }
        }
        Log.i(
                TAG,
                "Pixel downloadable service matches: package=" + packageName
                        + ", categoryServices=" + (serviceNames == null ? "all" : serviceNames.size())
                        + ", packageMatches=" + matchingPackageServices
                        + ", categoryMatches=" + matchingCategoryServices
                        + ", pendingMatches=" + matchingPendingServices
                        + ", wallpaperInfos=" + wallpapers.size());
        return wallpapers;
    }

    private static Set<String> getPendingComponents(Context context) {
        Set<String> components = new HashSet<>();
        try (Cursor cursor = context.getContentResolver().query(
                WALLPAPERS_URI,
                new String[] {COLUMN_TO_BE_INSTALLED},
                null,
                null,
                null)) {
            if (cursor == null) {
                return components;
            }
            int column = cursor.getColumnIndex(COLUMN_TO_BE_INSTALLED);
            if (column < 0) {
                return components;
            }
            while (cursor.moveToNext()) {
                String component = cursor.getString(column);
                if (component != null) {
                    components.add(component);
                }
            }
            Log.i(TAG, "Read " + components.size() + " pending components from Pixel provider");
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to query pending Pixel wallpapers", e);
        }
        return components;
    }
}
