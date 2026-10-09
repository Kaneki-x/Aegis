package com.beemdevelopment.aegis.widget;

import com.beemdevelopment.aegis.Preferences;
import com.beemdevelopment.aegis.vault.VaultManager;

import dagger.hilt.EntryPoint;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;

@EntryPoint
@InstallIn(SingletonComponent.class)
public interface WidgetEntryPoint {
    VaultManager getVaultManager();
    Preferences getPreferences();
}
