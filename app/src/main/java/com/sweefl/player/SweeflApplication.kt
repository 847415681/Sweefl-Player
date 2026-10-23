package com.sweefl.player

import com.sweefl.player.di.AppContainer

import android.app.Application

class SweeflApplication : Application() {
    val container by lazy { AppContainer(this) }
}
