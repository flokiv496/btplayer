package com.example.btplayer

import android.service.notification.NotificationListenerService

/** Пустой сервис: сам факт его включения даёт приложению право читать MediaSession. */
class MediaListenerService : NotificationListenerService()
