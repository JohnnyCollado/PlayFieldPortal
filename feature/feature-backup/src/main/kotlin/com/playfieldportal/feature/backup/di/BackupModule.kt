package com.playfieldportal.feature.backup.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

// BackupWorker and RestoreWorker are @HiltWorker — registered automatically via HiltWorkerFactory.
// WorkerFactory is bound in PFPApplication.workManagerConfiguration; no additional bindings needed here.
@Module
@InstallIn(SingletonComponent::class)
object BackupModule
