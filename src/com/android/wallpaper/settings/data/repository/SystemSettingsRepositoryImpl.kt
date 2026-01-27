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

package com.android.wallpaper.settings.data.repository

import android.content.ContentResolver
import android.database.ContentObserver
import android.provider.Settings
import com.android.systemui.shared.settings.data.repository.SystemSettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Simple implementation of [SystemSettingsRepository], suitable for a single-userid environment
 * (i.e. not SystemUI).
 */
class SystemSettingsRepositoryImpl(
    private val contentResolver: ContentResolver,
    private val backgroundDispatcher: CoroutineDispatcher,
) : SystemSettingsRepository {

    override fun intSetting(name: String, defaultValue: Int): Flow<Int> {
        return callbackFlow {
                val observer =
                    object : ContentObserver(null) {
                        override fun onChange(selfChange: Boolean) {
                            trySend(Unit)
                        }
                    }

                contentResolver.registerContentObserver(
                    Settings.System.getUriFor(name),
                    /* notifyForDescendants= */ false,
                    observer,
                )
                send(Unit)

                awaitClose { contentResolver.unregisterContentObserver(observer) }
            }
            .map { Settings.System.getInt(contentResolver, name, defaultValue) }
            // The above work is done on the background thread (which is important for accessing
            // settings through the content resolver).
            .flowOn(backgroundDispatcher)
    }

    override fun boolSetting(name: String, defaultValue: Boolean): Flow<Boolean> {
        return intSetting(name, if (defaultValue) 1 else 0).map { it != 0 }
    }

    override fun stringSetting(name: String, defaultValue: String?): Flow<String?> {
        return callbackFlow {
                fun trySendString() {
                    trySend(Settings.System.getString(contentResolver, name) ?: defaultValue)
                }
                val observer =
                    object : ContentObserver(null) {
                        override fun onChange(selfChange: Boolean) {
                            trySendString()
                        }
                    }

                contentResolver.registerContentObserver(
                    Settings.System.getUriFor(name),
                    /* notifyForDescendants= */ false,
                    observer,
                )
                trySendString()

                awaitClose { contentResolver.unregisterContentObserver(observer) }
            }
            .flowOn(backgroundDispatcher)
    }

    override suspend fun setInt(name: String, value: Int) {
        withContext(backgroundDispatcher) { Settings.System.putInt(contentResolver, name, value) }
    }

    override suspend fun getInt(name: String, defaultValue: Int): Int {
        return withContext(backgroundDispatcher) {
            Settings.System.getInt(contentResolver, name, defaultValue)
        }
    }

    override suspend fun getString(name: String): String? {
        return withContext(backgroundDispatcher) {
            Settings.System.getString(contentResolver, name)
        }
    }
}
