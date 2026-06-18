package com.playfieldportal.core.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

// Single app-wide DataStore instance — accessed as context.pfpDataStore
val Context.pfpDataStore: DataStore<Preferences> by preferencesDataStore(name = "pfp_prefs")
