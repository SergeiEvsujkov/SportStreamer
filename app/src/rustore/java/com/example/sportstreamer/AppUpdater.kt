package com.example.sportstreamer

import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ru.rustore.sdk.appupdate.model.AppUpdateOptions
import ru.rustore.sdk.appupdate.manager.factory.RuStoreAppUpdateManagerFactory
import ru.rustore.sdk.appupdate.model.UpdateAvailability

/**
 * Проверка обновлений через RuStore.
 *
 * Работает только в сборке, установленной из RuStore, при авторизованном пользователе.
 * Ошибки намеренно не показываются: обновление не должно мешать снимать матч.
 */
object AppUpdater {

    private const val TAG = "AppUpdater"

    /** Проверяет обновление и, если оно есть, предлагает обновиться. */
    fun checkForUpdate(activity: AppCompatActivity) {
        try {
            val manager = RuStoreAppUpdateManagerFactory.create(activity)
            manager.getAppUpdateInfo()
                .addOnSuccessListener { info ->
                    if (info.updateAvailability != UpdateAvailability.UPDATE_AVAILABLE) {
                        return@addOnSuccessListener
                    }
                    MaterialAlertDialogBuilder(activity)
                        .setTitle(R.string.update_available_title)
                        .setMessage(R.string.update_available_message)
                        .setPositiveButton(R.string.update_available_yes) { _, _ ->
                            manager.startUpdateFlow(info, AppUpdateOptions.Builder().build())
                                .addOnFailureListener { error ->
                                    Log.w(TAG, "Не удалось запустить обновление", error)
                                }
                        }
                        .setNegativeButton(R.string.update_available_later, null)
                        .show()
                }
                .addOnFailureListener { error ->
                    Log.w(TAG, "Проверка обновлений недоступна", error)
                }
        } catch (e: Throwable) {
            // В сборке не из RuStore SDK может быть недоступен — это нормально.
            Log.w(TAG, "RuStore SDK недоступен", e)
        }
    }
}
