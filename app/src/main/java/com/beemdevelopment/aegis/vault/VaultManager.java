package com.beemdevelopment.aegis.vault;

import android.app.Activity;
import android.app.backup.BackupManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ProcessLifecycleOwner;

import com.beemdevelopment.aegis.BackupsVersioningStrategy;
import com.beemdevelopment.aegis.Preferences;
import com.beemdevelopment.aegis.R;
import com.beemdevelopment.aegis.crypto.KeyStoreHandle;
import com.beemdevelopment.aegis.crypto.KeyStoreHandleException;
import com.beemdevelopment.aegis.database.AuditLogRepository;
import com.beemdevelopment.aegis.services.NotificationService;
import com.beemdevelopment.aegis.ui.dialogs.Dialogs;
import com.beemdevelopment.aegis.widget.AegisWidgetProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class VaultManager {
    private static final long EXTERNAL_UNLOCK_HOLD_MILLIS = 10_000;

    private final Context _context;
    private final Preferences _prefs;

    private VaultRepository _repo;

    private final VaultBackupManager _backups;
    private final BackupManager _androidBackups;

    private final List<LockListener> _lockListeners;
    private boolean _blockAutoLock;

    private final AuditLogRepository _auditLogRepository;

    // State for external access to the vault (input method & home screen widget).
    // Every hold is either tied to a visible window (_imeVisible) or time-bounded.
    private final Handler _externalHandler = new Handler(Looper.getMainLooper());
    private final Runnable _externalExpiryRunnable = this::onExternalHoldExpired;
    private boolean _imeVisible;
    private long _widgetRevealUntil;
    private long _externalGraceUntil;

    public VaultManager(@NonNull Context context, AuditLogRepository auditLogRepository) {
        _context = context;
        _prefs = new Preferences(_context);
        _backups = new VaultBackupManager(_context, auditLogRepository);
        _androidBackups = new BackupManager(context);
        _lockListeners = new ArrayList<>();
        _auditLogRepository = auditLogRepository;
    }

    /**
     * Initializes the vault repository with a new empty vault and the given creds. It can
     * only be called if isVaultLoaded() returns false.
     */
    @NonNull
    public VaultRepository initNew(@Nullable VaultFileCredentials creds) throws VaultRepositoryException {
        if (isVaultLoaded()) {
            throw new IllegalStateException("Vault manager is already initialized");
        }

        VaultRepository repo = new VaultRepository(_context, new Vault(), creds);
        repo.save();
        _repo = repo;

        if (getVault().isEncryptionEnabled()) {
            startNotificationService();
        }

        AegisWidgetProvider.onVaultStateChanged(_context);
        return getVault();
    }

    /**
     * Initializes the vault repository by decrypting the given vaultFile with the given
     * creds. It can only be called if isVaultLoaded() returns false.
     */
    @NonNull
    public VaultRepository loadFrom(@NonNull VaultFile vaultFile, @Nullable VaultFileCredentials creds) throws VaultRepositoryException {
        if (isVaultLoaded()) {
            throw new IllegalStateException("Vault manager is already initialized");
        }

        _repo = VaultRepository.fromFile(_context, vaultFile, creds);

        if (getVault().isEncryptionEnabled()) {
            startNotificationService();
        }

        AegisWidgetProvider.onVaultStateChanged(_context);
        return getVault();
    }

    @NonNull
    public VaultRepository loadFrom(@NonNull VaultFile vaultFile) throws VaultRepositoryException {
        return loadFrom(vaultFile, null);
    }

    /**
     * Locks the vault and the app.
     * @param userInitiated whether or not the user initiated the lock in MainActivity.
     */
    public void lock(boolean userInitiated) {
        _repo = null;
        clearExternalHolds();

        for (LockListener listener : _lockListeners) {
            listener.onLocked(userInitiated);
        }

        stopNotificationService();
        AegisWidgetProvider.onVaultStateChanged(_context);
    }

    public void enableEncryption(VaultFileCredentials creds) throws VaultRepositoryException {
        getVault().setCredentials(creds);
        saveAndBackup();
        startNotificationService();
    }

    public void disableEncryption() throws VaultRepositoryException {
        getVault().setCredentials(null);
        save();

        // remove any keys that are stored in the KeyStore
        try {
            KeyStoreHandle handle = new KeyStoreHandle();
            handle.clear();
        } catch (KeyStoreHandleException e) {
            // this cleanup operation is not strictly necessary, so we ignore any exceptions here
            e.printStackTrace();
        }

        stopNotificationService();
    }

    public void save() throws VaultRepositoryException {
        getVault().save();

        // entries, favorites or icons may have changed, keep the home screen widgets in sync
        AegisWidgetProvider.updateAll(_context);
    }

    public void saveAndBackup() throws VaultRepositoryException {
        save();

        boolean backedUp = false;
        if (getVault().isEncryptionEnabled()) {
            if (_prefs.isBackupsEnabled()) {
                backedUp = true;
                try {
                    scheduleBackup();
                } catch (VaultRepositoryException e) {
                    _prefs.setBuiltInBackupResult(new Preferences.BackupResult(e));
                }
            }

            if (_prefs.isAndroidBackupsEnabled()) {
                backedUp = true;
                scheduleAndroidBackup();
            }
        }

        if (!backedUp) {
            _prefs.setIsBackupReminderNeeded(true);
        }
    }

    public void scheduleBackup() throws VaultRepositoryException {
        _prefs.setIsBackupReminderNeeded(false);

        try {
            File dir = new File(_context.getCacheDir(), "backup");
            if (!dir.exists() && !dir.mkdir()) {
                throw new IOException(String.format("Unable to create directory %s", dir));
            }

            File tempFile = File.createTempFile(VaultBackupManager.FILENAME_PREFIX, ".json", dir);
            try (OutputStream outStream = new FileOutputStream(tempFile)) {
                _repo.export(outStream);
            }
            BackupsVersioningStrategy strategy = _prefs.getBackupVersioningStrategy();
            Uri uri = _prefs.getBackupsLocation();
            int versionsToKeep = _prefs.getBackupsVersionCount();

            _backups.scheduleBackup(tempFile, strategy, uri, versionsToKeep);
        } catch (IOException e) {
            throw new VaultRepositoryException(e);
        }
    }

    public void scheduleAndroidBackup() {
        _prefs.setIsBackupReminderNeeded(false);
        _androidBackups.dataChanged();
    }

    public boolean isAutoLockEnabled(int autoLockType) {
        return _prefs.isAutoLockTypeEnabled(autoLockType)
                && isVaultLoaded()
                && getVault().isEncryptionEnabled();
    }

    public void registerLockListener(LockListener listener) {
        _lockListeners.add(listener);
    }

    public void unregisterLockListener(LockListener listener) {
        _lockListeners.remove(listener);
    }

    /**
     * Sets whether to block automatic lock on minimization. This should only be called
     * by activities before invoking an intent that shows a DocumentsUI, because that
     * action leads AppLifecycleObserver to believe that the app has been minimized.
     */
    public void setBlockAutoLock(boolean block) {
        _blockAutoLock = block;
    }

    /**
     * Reports whether automatic lock on minimization is currently blocked.
     */
    public boolean isAutoLockBlocked() {
        return _blockAutoLock;
    }

    public boolean isVaultLoaded() {
        return _repo != null;
    }

    /**
     * Reports whether the input method is currently showing its picker. While it is
     * visible, automatic lock on minimization is suppressed.
     */
    public void setInputMethodVisible(boolean visible) {
        if (_imeVisible == visible) {
            return;
        }

        _imeVisible = visible;
        if (visible) {
            _externalHandler.removeCallbacks(_externalExpiryRunnable);
        } else {
            startExternalGrace();
            evaluateExternalLock();
        }
    }

    /**
     * Keeps the vault accessible for the home screen widget for the given amount of time.
     */
    public void holdForWidget(long millis) {
        _widgetRevealUntil = Math.max(_widgetRevealUntil, System.currentTimeMillis() + millis);
        evaluateExternalLock();
    }

    /**
     * Called right after the vault was unlocked on behalf of an external component. Holds
     * the vault for at least a short moment, so that the lifecycle ON_STOP event caused by
     * the disappearing AuthActivity does not immediately lock it again, and for the
     * configured grace period, since unlocking counts as using the component.
     */
    public void armExternalHold() {
        long hold = Math.max(EXTERNAL_UNLOCK_HOLD_MILLIS, _prefs.getExternalAccessGraceMillis());
        _externalGraceUntil = Math.max(_externalGraceUntil, System.currentTimeMillis() + hold);
        evaluateExternalLock();
    }

    /**
     * Reports whether an external component (input method or widget) currently holds the
     * vault open, which suppresses automatic lock on minimization.
     */
    public boolean isExternalAccessHeld() {
        long now = System.currentTimeMillis();
        return _imeVisible || now < _widgetRevealUntil || now < _externalGraceUntil;
    }

    private void startExternalGrace() {
        long grace = _prefs.getExternalAccessGraceMillis();
        if (grace > 0 && isVaultLoaded()) {
            _externalGraceUntil = Math.max(_externalGraceUntil, System.currentTimeMillis() + grace);
        }
    }

    private void onExternalHoldExpired() {
        long now = System.currentTimeMillis();
        if (_widgetRevealUntil != 0 && now >= _widgetRevealUntil) {
            _widgetRevealUntil = 0;
            startExternalGrace();
        }
        if (_externalGraceUntil != 0 && now >= _externalGraceUntil) {
            _externalGraceUntil = 0;
        }

        evaluateExternalLock();
    }

    /**
     * Locks the vault if no external hold is active anymore, the app itself is not in the
     * foreground and the user wants the vault to be locked when the app is minimized.
     * Otherwise, schedules a re-evaluation for when the next time-based hold expires.
     */
    private void evaluateExternalLock() {
        _externalHandler.removeCallbacks(_externalExpiryRunnable);
        if (!isAutoLockEnabled(Preferences.AUTO_LOCK_ON_MINIMIZE) || isAutoLockBlocked()) {
            return;
        }

        if (isExternalAccessHeld()) {
            long now = System.currentTimeMillis();
            long next = Long.MAX_VALUE;
            if (_widgetRevealUntil > now) {
                next = Math.min(next, _widgetRevealUntil);
            }
            if (_externalGraceUntil > now) {
                next = Math.min(next, _externalGraceUntil);
            }
            if (next != Long.MAX_VALUE) {
                _externalHandler.postDelayed(_externalExpiryRunnable, next - now + 50);
            }
            return;
        }

        if (isAppInForeground()) {
            return;
        }

        lock(false);
    }

    private void clearExternalHolds() {
        _externalHandler.removeCallbacks(_externalExpiryRunnable);
        _widgetRevealUntil = 0;
        _externalGraceUntil = 0;
    }

    private static boolean isAppInForeground() {
        return ProcessLifecycleOwner.get().getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED);
    }

    public boolean isVaultInitNeeded() {
        return !isVaultLoaded() && !VaultRepository.fileExists(_context);
    }

    /**
     * Loads the vault if it isn't loaded yet and doesn't require credentials. Used by
     * components that run without an Activity (input method, widget) to avoid sending the
     * user through AuthActivity for a vault that isn't encrypted.
     * @return whether the vault is loaded afterwards.
     */
    public boolean loadIfUnencrypted() {
        if (isVaultLoaded()) {
            return true;
        }

        if (!VaultRepository.fileExists(_context)) {
            return false;
        }

        try {
            VaultFile vaultFile = VaultRepository.readVaultFile(_context);
            if (vaultFile.isEncrypted()) {
                return false;
            }

            loadFrom(vaultFile);
            return true;
        } catch (VaultRepositoryException e) {
            e.printStackTrace();
            return false;
        }
    }

    @NonNull
    public VaultRepository getVault() {
        if (!isVaultLoaded()) {
            throw new IllegalStateException("Vault manager is not initialized");
        }

        return _repo;
    }

    /**
     * Starts an external activity, temporarily blocks automatic lock of Aegis and
     * shows an error dialog if the target activity is not found.
     */
    public void fireIntentLauncher(Activity activity, Intent intent, ActivityResultLauncher<Intent> resultLauncher) {
        setBlockAutoLock(true);

        try {
            resultLauncher.launch(intent);
        } catch (ActivityNotFoundException e) {
            e.printStackTrace();

            if (isDocsAction(intent.getAction())) {
                Dialogs.showErrorDialog(activity, R.string.documentsui_error, e);
            } else {
                throw e;
            }
        }
    }

    /**
     * Starts an external activity, temporarily blocks automatic lock of Aegis and
     * shows an error dialog if the target activity is not found.
     */
    public void fireIntentLauncher(Fragment fragment, Intent intent, ActivityResultLauncher<Intent> resultLauncher) {
        setBlockAutoLock(true);

        try {
            resultLauncher.launch(intent);
        } catch (ActivityNotFoundException e) {
            e.printStackTrace();

            if (isDocsAction(intent.getAction())) {
                Dialogs.showErrorDialog(fragment.requireContext(), R.string.documentsui_error, e);
            } else {
                throw e;
            }
        }
    }

    private void startNotificationService() {
        // NOTE: Disabled for now. See issue: #1047
        /*if (PermissionHelper.granted(_context, Manifest.permission.POST_NOTIFICATIONS)) {
            _context.startService(getNotificationServiceIntent());
        }*/
    }

    private void stopNotificationService() {
        // NOTE: Disabled for now. See issue: #1047
        //_context.stopService(getNotificationServiceIntent());
    }

    private Intent getNotificationServiceIntent() {
        return new Intent(_context, NotificationService.class);
    }

    private static boolean isDocsAction(@Nullable String action) {
        return action != null && (action.equals(Intent.ACTION_GET_CONTENT)
                || action.equals(Intent.ACTION_CREATE_DOCUMENT)
                || action.equals(Intent.ACTION_OPEN_DOCUMENT)
                || action.equals(Intent.ACTION_OPEN_DOCUMENT_TREE));
    }

    public interface LockListener {
        /**
         * Called when the vault lock status changes
         * @param userInitiated whether or not the user initiated the lock in MainActivity.
         */
        void onLocked(boolean userInitiated);
    }
}
