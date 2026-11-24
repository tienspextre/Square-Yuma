package dev.lelonio.square.common

import timber.log.Timber

actual object Log {
    actual fun i(tag: String, msg: String) = Timber.tag(tag).i(msg)
    actual fun d(tag: String, msg: String) = Timber.tag(tag).d(msg)
    actual fun w(tag: String, msg: String) = Timber.tag(tag).w(msg)
    actual fun w(tag: String, t: Throwable, msg: String) = Timber.tag(tag).w(t, msg)
    actual fun e(tag: String, msg: String) = Timber.tag(tag).e(msg)
    actual fun e(tag: String, t: Throwable, msg: String) = Timber.tag(tag).e(t, msg)
}
