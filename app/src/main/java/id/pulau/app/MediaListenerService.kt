package id.pulau.app

import android.service.notification.NotificationListenerService

/**
 * Layanan kosong. Keberadaannya (dan izin akses notifikasi dari pengguna)
 * dibutuhkan Android agar aplikasi boleh membaca sesi media yang sedang aktif.
 */
class MediaListenerService : NotificationListenerService()
